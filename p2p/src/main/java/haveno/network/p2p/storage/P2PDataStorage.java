/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package haveno.network.p2p.storage;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Maps;
import com.google.inject.Inject;
import com.google.inject.name.Named;
import com.google.protobuf.ByteString;
import haveno.common.Timer;
import haveno.common.UserThread;
import haveno.common.app.Capabilities;
import haveno.common.crypto.CryptoException;
import haveno.common.crypto.Hash;
import haveno.common.crypto.Sig;
import haveno.common.persistence.PersistenceManager;
import haveno.common.proto.network.GetDataResponsePriority;
import haveno.common.proto.network.NetworkEnvelope;
import haveno.common.proto.network.NetworkPayload;
import haveno.common.proto.persistable.PersistablePayload;
import haveno.common.proto.persistable.PersistedDataHost;
import haveno.common.util.Hex;
import haveno.common.util.Tuple2;
import haveno.common.util.Utilities;
import haveno.network.p2p.NodeAddress;
import haveno.network.p2p.network.CloseConnectionReason;
import haveno.network.p2p.network.Connection;
import haveno.network.p2p.network.ConnectionListener;
import haveno.network.p2p.network.MessageListener;
import haveno.network.p2p.network.NetworkNode;
import haveno.network.p2p.peers.BroadcastHandler;
import haveno.network.p2p.peers.Broadcaster;
import haveno.network.p2p.peers.getdata.messages.GetDataRequest;
import haveno.network.p2p.peers.getdata.messages.GetDataResponse;
import haveno.network.p2p.peers.getdata.messages.GetUpdatedDataRequest;
import haveno.network.p2p.peers.getdata.messages.PreliminaryGetDataRequest;
import haveno.network.p2p.storage.messages.AddDataMessage;
import haveno.network.p2p.storage.messages.AddOncePayload;
import haveno.network.p2p.storage.messages.AddPersistableNetworkPayloadMessage;
import haveno.network.p2p.storage.messages.BroadcastMessage;
import haveno.network.p2p.storage.messages.RefreshOfferMessage;
import haveno.network.p2p.storage.messages.RemoveDataMessage;
import haveno.network.p2p.storage.messages.RemoveMailboxDataMessage;
import haveno.network.p2p.storage.payload.CapabilityRequiringPayload;
import haveno.network.p2p.storage.payload.DateSortedTruncatablePayload;
import haveno.network.p2p.storage.payload.DateTolerantPayload;
import haveno.network.p2p.storage.payload.MailboxStoragePayload;
import haveno.network.p2p.storage.payload.PersistableNetworkPayload;
import haveno.network.p2p.storage.payload.ProcessOncePersistableNetworkPayload;
import haveno.network.p2p.storage.payload.ProtectedMailboxStorageEntry;
import haveno.network.p2p.storage.payload.ProtectedStorageEntry;
import haveno.network.p2p.storage.payload.ProtectedStoragePayload;
import haveno.network.p2p.storage.payload.RequiresOwnerIsOnlinePayload;
import haveno.network.p2p.storage.persistence.AppendOnlyDataStoreListener;
import haveno.network.p2p.storage.persistence.AppendOnlyDataStoreService;
import haveno.network.p2p.storage.persistence.HistoricalDataStoreService;
import haveno.network.p2p.storage.persistence.PersistableNetworkPayloadStore;
import haveno.network.p2p.storage.persistence.ProtectedDataStoreService;
import haveno.network.p2p.storage.persistence.RemovedPayloadsService;
import haveno.network.p2p.storage.persistence.ResourceDataStoreService;
import haveno.network.p2p.storage.persistence.SequenceNumberMap;
import java.security.KeyPair;
import java.security.PublicKey;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javax.annotation.Nullable;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.fxmisc.easybind.EasyBind;
import org.fxmisc.easybind.monadic.MonadicBinding;

@Slf4j
public class P2PDataStorage implements MessageListener, ConnectionListener, PersistedDataHost {
    /**
     * How many days to keep an entry before it is purged.
     */
    @VisibleForTesting
    public static final int PURGE_AGE_DAYS = 10;

    @VisibleForTesting
    public static final int CHECK_TTL_INTERVAL_SEC = 60;

    private boolean initialRequestApplied = false;

    private final Broadcaster broadcaster;
    @VisibleForTesting
    final AppendOnlyDataStoreService appendOnlyDataStoreService;
    private final ProtectedDataStoreService protectedDataStoreService;
    private final ResourceDataStoreService resourceDataStoreService;

    @Getter
    private final Map<ByteArray, ProtectedStorageEntry> map = new ConcurrentHashMap<>();
    private final Set<HashMapChangedListener> hashMapChangedListeners = new CopyOnWriteArraySet<>();
    private Timer removeExpiredEntriesTimer;

    private final PersistenceManager<SequenceNumberMap> persistenceManager;

    @VisibleForTesting
    final SequenceNumberMap sequenceNumberMap = new SequenceNumberMap();

    private final Set<AppendOnlyDataStoreListener> appendOnlyDataStoreListeners = new CopyOnWriteArraySet<>();
    private final RemovedPayloadsService removedPayloadsService;
    private final Clock clock;

    /// The maximum number of items that must exist in the SequenceNumberMap before it is scheduled for a purge
    /// which removes entries after PURGE_AGE_DAYS.
    private final int maxSequenceNumberMapSizeBeforePurge;

    // Don't convert to local variable as it might get GC'ed.
    private MonadicBinding<Boolean> readFromResourcesCompleteBinding;

    @Setter
    private Predicate<ProtectedStoragePayload> filterPredicate; // Set from FilterManager

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    public P2PDataStorage(NetworkNode networkNode,
            Broadcaster broadcaster,
            AppendOnlyDataStoreService appendOnlyDataStoreService,
            ProtectedDataStoreService protectedDataStoreService,
            ResourceDataStoreService resourceDataStoreService,
            PersistenceManager<SequenceNumberMap> persistenceManager,
            RemovedPayloadsService removedPayloadsService,
            Clock clock,
            @Named("MAX_SEQUENCE_NUMBER_MAP_SIZE_BEFORE_PURGE") int maxSequenceNumberBeforePurge) {
        this.broadcaster = broadcaster;
        this.appendOnlyDataStoreService = appendOnlyDataStoreService;
        this.protectedDataStoreService = protectedDataStoreService;
        this.resourceDataStoreService = resourceDataStoreService;
        this.persistenceManager = persistenceManager;
        this.removedPayloadsService = removedPayloadsService;
        this.clock = clock;
        this.maxSequenceNumberMapSizeBeforePurge = maxSequenceNumberBeforePurge;

        networkNode.addMessageListener(this);
        networkNode.addConnectionListener(this);

        this.persistenceManager.initialize(sequenceNumberMap, PersistenceManager.Source.PRIVATE_LOW_PRIO);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // PersistedDataHost
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void readPersisted(Runnable completeHandler) {
        persistenceManager.readPersisted(persisted -> {
            synchronized (persisted.getMap()) {
                sequenceNumberMap.setMap(getPurgedSequenceNumberMap(persisted.getMap()));
            }
            completeHandler.run();
        },
                completeHandler);
    }

    // Uses synchronous execution on the userThread. Only used by tests. The async methods should be used by app code.
    @VisibleForTesting
    public void readPersistedSync() {
        SequenceNumberMap persisted = persistenceManager.getPersisted();
        if (persisted != null) {
            synchronized (persisted.getMap()) {
                sequenceNumberMap.setMap(getPurgedSequenceNumberMap(persisted.getMap()));
            }
        }
    }

    // Threading is done on the persistenceManager level
    public void readFromResources(String postFix, Runnable completeHandler) {
        BooleanProperty appendOnlyDataStoreServiceReady = new SimpleBooleanProperty();
        BooleanProperty protectedDataStoreServiceReady = new SimpleBooleanProperty();
        BooleanProperty resourceDataStoreServiceReady = new SimpleBooleanProperty();

        readFromResourcesCompleteBinding = EasyBind.combine(appendOnlyDataStoreServiceReady,
                protectedDataStoreServiceReady,
                resourceDataStoreServiceReady,
                (a, b, c) -> a && b && c);
        readFromResourcesCompleteBinding.subscribe((observable, oldValue, newValue) -> {
            if (newValue) {
                completeHandler.run();
            }
        });

        appendOnlyDataStoreService.readFromResources(postFix, () -> appendOnlyDataStoreServiceReady.set(true));
        protectedDataStoreService.readFromResources(postFix, () -> {
            synchronized (map) {
                map.putAll(protectedDataStoreService.getMap());
                protectedDataStoreServiceReady.set(true);
            }
        });
        resourceDataStoreService.readFromResources(postFix, () -> resourceDataStoreServiceReady.set(true));
    }

    // Uses synchronous execution on the userThread. Only used by tests. The async methods should be used by app code.
    @VisibleForTesting
    public void readFromResourcesSync(String postFix) {
        synchronized (map) {
            appendOnlyDataStoreService.readFromResourcesSync(postFix);
            protectedDataStoreService.readFromResourcesSync(postFix);
            resourceDataStoreService.readFromResourcesSync(postFix);

            map.putAll(protectedDataStoreService.getMap());
        }
    }

    // We get added mailbox message data from MailboxMessageService. We want to add those early so we can get it added
    // to our excluded keys to reduce initial data response data size.
    public void addProtectedMailboxStorageEntryToMap(ProtectedStorageEntry protectedStorageEntry) {
        synchronized (map) {
            ProtectedStoragePayload protectedStoragePayload = protectedStorageEntry.getProtectedStoragePayload();
            ByteArray hashOfPayload = get32ByteHashAsByteArray(protectedStoragePayload);
            map.put(hashOfPayload, protectedStorageEntry);
            //log.trace("## addProtectedMailboxStorageEntryToMap hashOfPayload={}, map={}", hashOfPayload, printMap());
        }
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // RequestData API
    ///////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Returns a PreliminaryGetDataRequest that can be sent to a peer node to request missing Payload data.
     */
    public PreliminaryGetDataRequest buildPreliminaryGetDataRequest(int nonce) {
        return new PreliminaryGetDataRequest(nonce, getKnownPayloadHashes());
    }

    /**
     * Returns a GetUpdatedDataRequest that can be sent to a peer node to request missing Payload data.
     */
    public GetUpdatedDataRequest buildGetUpdatedDataRequest(NodeAddress senderNodeAddress, int nonce) {
        return new GetUpdatedDataRequest(senderNodeAddress, nonce, getKnownPayloadHashes());
    }

    /**
     * Returns the set of known payload hashes. This is used in the GetData path to request missing data from peer nodes
     */
    private Set<byte[]> getKnownPayloadHashes() {
        // We collect the keys of the PersistableNetworkPayload items so we exclude them in our request.
        // PersistedStoragePayload items don't get removed, so we don't have an issue with the case that
        // an object gets removed in between PreliminaryGetDataRequest and the GetUpdatedDataRequest and we would
        // miss that event if we do not load the full set or use some delta handling.
        Map<ByteArray, PersistableNetworkPayload> mapForDataRequest = getMapForDataRequest();
        Set<byte[]> excludedKeys = getKeysAsByteSet(mapForDataRequest);
        Set<byte[]> excludedKeysFromProtectedStorageEntryMap = getKeysAsByteSet(map);
        excludedKeys.addAll(excludedKeysFromProtectedStorageEntryMap);
        return excludedKeys;
    }

    /**
     * Returns a GetDataResponse object that contains the Payloads known locally, but not remotely.
     */
    public GetDataResponse buildGetDataResponse(
            GetDataRequest getDataRequest,
            int maxEntriesPerType,
            AtomicBoolean wasPersistableNetworkPayloadsTruncated,
            AtomicBoolean wasProtectedStorageEntriesTruncated,
            Capabilities peerCapabilities) {

        Set<P2PDataStorage.ByteArray> excludedKeysAsByteArray =
                P2PDataStorage.ByteArray.convertBytesSetToByteArraySet(getDataRequest.getExcludedKeys());

        // Pre v 1.4.0 requests do not have set the requesters version field so it is null.
        // The methods in HistoricalDataStoreService will return all historical data in that case.
        // mapForDataResponse contains the filtered by version data from HistoricalDataStoreService as well as all other
        // maps of the remaining appendOnlyDataStoreServices.
        Map<ByteArray, PersistableNetworkPayload> mapForDataResponse = getMapForDataResponse(getDataRequest.getVersion());

        // Give a bit of tolerance for message overhead
        double maxSize = Connection.getMaxPermittedMessageSize() * 0.6;

        // 25% of space is allocated for PersistableNetworkPayloads
        long limit = Math.round(maxSize * 0.25);
        Set<PersistableNetworkPayload> filteredPersistableNetworkPayloads = filterKnownHashes(
                mapForDataResponse,
                Function.identity(),
                excludedKeysAsByteArray,
                peerCapabilities,
                maxEntriesPerType,
                limit,
                wasPersistableNetworkPayloadsTruncated,
                true);
        log.info("{} PersistableNetworkPayload entries remained after filtered by excluded keys. " +
                "Original map had {} entries.",
                filteredPersistableNetworkPayloads.size(), mapForDataResponse.size());
        log.trace("## buildGetDataResponse filteredPersistableNetworkPayloadHashes={}",
                filteredPersistableNetworkPayloads.stream()
                        .map(e -> Utilities.encodeToHex(e.getHash()))
                        .toArray());

        // We give 75% space to ProtectedStorageEntries as they contain MailBoxMessages and those can be larger.
        limit = Math.round(maxSize * 0.75);
        Set<ProtectedStorageEntry> filteredProtectedStorageEntries = filterKnownHashes(
                map,
                ProtectedStorageEntry::getProtectedStoragePayload,
                excludedKeysAsByteArray,
                peerCapabilities,
                maxEntriesPerType,
                limit,
                wasProtectedStorageEntriesTruncated,
                false);
        log.info("{} ProtectedStorageEntry entries remained after filtered by excluded keys. " +
                        "Original map had {} entries.",
                filteredProtectedStorageEntries.size(), map.size());
        log.trace("## buildGetDataResponse filteredProtectedStorageEntryHashes={}",
                filteredProtectedStorageEntries.stream()
                        .map(e -> get32ByteHashAsByteArray((e.getProtectedStoragePayload())))
                        .toArray());

        boolean wasTruncated = wasPersistableNetworkPayloadsTruncated.get() || wasProtectedStorageEntriesTruncated.get();
        return new GetDataResponse(
                filteredProtectedStorageEntries,
                filteredPersistableNetworkPayloads,
                getDataRequest.getNonce(),
                getDataRequest instanceof GetUpdatedDataRequest,
                wasTruncated);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Utils for collecting the exclude hashes
    ///////////////////////////////////////////////////////////////////////////////////////////

    private Map<ByteArray, PersistableNetworkPayload> getMapForDataRequest() {
        Map<ByteArray, PersistableNetworkPayload> map = new HashMap<>();
        appendOnlyDataStoreService.getServices()
                .forEach(service -> {
                    Map<ByteArray, PersistableNetworkPayload> serviceMap;
                    if (service instanceof HistoricalDataStoreService) {
                        var historicalDataStoreService = (HistoricalDataStoreService<? extends PersistableNetworkPayloadStore>) service;
                        // As we add the version to our request we only use the live data. Eventually missing data will be
                        // derived from the version.
                        serviceMap = historicalDataStoreService.getMapOfLiveData();
                    } else {
                        serviceMap = service.getMap();
                    }
                    map.putAll(serviceMap);
                    log.debug("We added {} entries from {} to the excluded key set of our request",
                            serviceMap.size(), service.getClass().getSimpleName());
                });
        return map;
    }

    public Map<ByteArray, PersistableNetworkPayload> getMapForDataResponse(String requestersVersion) {
        Map<ByteArray, PersistableNetworkPayload> map = new HashMap<>();
        appendOnlyDataStoreService.getServices()
                .forEach(service -> {
                    Map<ByteArray, PersistableNetworkPayload> serviceMap;
                    if (service instanceof HistoricalDataStoreService) {
                        var historicalDataStoreService = (HistoricalDataStoreService<? extends PersistableNetworkPayloadStore>) service;
                        serviceMap = historicalDataStoreService.getMapSinceVersion(requestersVersion);
                    } else {
                        serviceMap = service.getMap();
                    }
                    map.putAll(serviceMap);
                    log.info("We added {} entries from {} to be filtered by excluded keys",
                            serviceMap.size(), service.getClass().getSimpleName());
                });
        return map;
    }

    /**
     * Generic function that can be used to filter a Map<ByteArray, ProtectedStorageEntry || PersistableNetworkPayload>
     * by a given set of keys and peer capabilities.
     */
    static private <T extends NetworkPayload> Set<T> filterKnownHashes(
            Map<ByteArray, T> toFilter,
            Function<T, ? extends NetworkPayload> asPayload,
            Set<ByteArray> knownHashes,
            Capabilities peerCapabilities,
            int maxEntries,
            long limit,
            AtomicBoolean outTruncated,
            boolean isPersistableNetworkPayload) {
        log.info("Filter {} data based on {} knownHashes",
                isPersistableNetworkPayload ? "PersistableNetworkPayload" : "ProtectedStorageEntry",
                knownHashes.size());

        AtomicLong totalSize = new AtomicLong();
        AtomicBoolean exceededSizeLimit = new AtomicBoolean();

        Set<Map.Entry<ByteArray, T>> entries = toFilter.entrySet();
        Map<String, AtomicInteger> numItemsByClassName = new HashMap<>();
        entries.forEach(entry -> {
            String name = asPayload.apply(entry.getValue()).getClass().getSimpleName();
            numItemsByClassName.putIfAbsent(name, new AtomicInteger());
            numItemsByClassName.get(name).incrementAndGet();
        });
        log.info("numItemsByClassName: {}", numItemsByClassName);

        // Map.Entry.value can be ProtectedStorageEntry or PersistableNetworkPayload. We call it item in the steam iterations.
        List<T> filteredItems = entries.stream()
                .filter(entry -> !knownHashes.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .filter(item -> shouldTransmitPayloadToPeer(peerCapabilities, asPayload.apply(item)))
                .collect(Collectors.toList());
        List<T> resultItems = new ArrayList<>();

        // Truncation follows this rules
        // 1. Add all payloads with GetDataResponsePriority.MID
        // 2. Add all payloads with GetDataResponsePriority.LOW && !DateSortedTruncatablePayload until exceededSizeLimit is reached
        // 3. if(!exceededSizeLimit) Add all payloads with GetDataResponsePriority.LOW && DateSortedTruncatablePayload until
        //    exceededSizeLimit is reached and truncate by maxItems (sorted by date). We add the sublist to our resultItems in
        //    reverse order so in case we cut off at next step we cut off oldest items.
        // 4. We truncate list if resultList size > maxEntries
        // 5. Add all payloads with GetDataResponsePriority.HIGH

        // 1. Add all payloads with GetDataResponsePriority.MID
        List<T> midPrioItems = filteredItems.stream()
                .filter(item -> item.getGetDataResponsePriority() == GetDataResponsePriority.MID)
                .collect(Collectors.toList());
        resultItems.addAll(midPrioItems);
        log.info("Number of items with GetDataResponsePriority.MID: {}", midPrioItems.size());

        // 2. Add all payloads with GetDataResponsePriority.LOW && !DateSortedTruncatablePayload until exceededSizeLimit is reached
        List<T> lowPrioItems = filteredItems.stream()
                .filter(item -> item.getGetDataResponsePriority() == GetDataResponsePriority.LOW)
                .filter(item -> !(asPayload.apply(item) instanceof DateSortedTruncatablePayload))
                .filter(item -> {
                    if (exceededSizeLimit.get()) {
                        return false;
                    }
                    if (totalSize.addAndGet(item.toProtoMessage().getSerializedSize()) > limit) {
                        exceededSizeLimit.set(true);
                        return false;
                    }
                    return true;
                })
                .collect(Collectors.toList());
        resultItems.addAll(lowPrioItems);
        log.info("Number of items with GetDataResponsePriority.LOW and !DateSortedTruncatablePayload: {}. Exceeded size limit: {}", lowPrioItems.size(), exceededSizeLimit.get());

        // 3. if(!exceededSizeLimit) Add all payloads with GetDataResponsePriority.LOW && DateSortedTruncatablePayload until
        //    exceededSizeLimit is reached and truncate by maxItems (sorted by date). We add the sublist to our resultItems in
        //    reverse order so in case we cut off at next step we cut off oldest items.
        if (!exceededSizeLimit.get()) {
            List<T> dateSortedItems = filteredItems.stream()
                    .filter(item -> item.getGetDataResponsePriority() == GetDataResponsePriority.LOW)
                    .filter(item -> asPayload.apply(item) instanceof DateSortedTruncatablePayload)
                    .filter(item -> {
                        if (exceededSizeLimit.get()) {
                            return false;
                        }
                        if (totalSize.addAndGet(item.toProtoMessage().getSerializedSize()) > limit) {
                            exceededSizeLimit.set(true);
                            return false;
                        }
                        return true;
                    })
                    .sorted(Comparator.comparing(item -> ((DateSortedTruncatablePayload) asPayload.apply(item)).getDate()))
                    .collect(Collectors.toList());
            if (!dateSortedItems.isEmpty()) {
                int maxItems = ((DateSortedTruncatablePayload) asPayload.apply(dateSortedItems.get(0))).maxItems();
                int size = dateSortedItems.size();
                if (size > maxItems) {
                    int fromIndex = size - maxItems;
                    dateSortedItems = dateSortedItems.subList(fromIndex, size);
                    outTruncated.set(true);
                    log.info("Num truncated dateSortedItems {}", size);
                    log.info("Removed oldest {} dateSortedItems as we exceeded {}", fromIndex, maxItems);
                }
            }
            log.info("Number of items with GetDataResponsePriority.LOW and DateSortedTruncatablePayload: {}. Was truncated: {}", dateSortedItems.size(), outTruncated.get());

            // We reverse sorting so in case we get truncated we cut off the older items
            Comparator<T> comparator = Comparator.comparing(item -> ((DateSortedTruncatablePayload) asPayload.apply(item)).getDate());
            dateSortedItems.sort(comparator.reversed());
            resultItems.addAll(dateSortedItems);
        } else {
            log.info("No dateSortedItems added as we exceeded already the exceededSizeLimit of {}", limit);
        }

        // 4. We truncate list if resultList size > maxEntries
        int size = resultItems.size();
        if (size > maxEntries) {
            resultItems = resultItems.subList(0, maxEntries);
            outTruncated.set(true);
            log.info("Removed last {} items as we exceeded {}", size - maxEntries, maxEntries);
        }

        outTruncated.set(outTruncated.get() || exceededSizeLimit.get());

        // 5. Add all payloads with GetDataResponsePriority.HIGH
        List<T> highPrioItems = filteredItems.stream()
                .filter(item -> item.getGetDataResponsePriority() == GetDataResponsePriority.HIGH)
                .collect(Collectors.toList());
        resultItems.addAll(highPrioItems);
        log.info("Number of items with GetDataResponsePriority.HIGH: {}", highPrioItems.size());
        log.info("Number of result items we send to requester: {}", resultItems.size());
        return new HashSet<>(resultItems);
    }

    public Collection<PersistableNetworkPayload> getPersistableNetworkPayloadCollection() {
        return getMapForDataRequest().values();
    }

    private Set<byte[]> getKeysAsByteSet(Map<ByteArray, ? extends PersistablePayload> map) {
        return map.keySet().stream()
                .map(e -> e.bytes)
                .collect(Collectors.toSet());
    }

    /**
     * Returns true if a Payload should be transmit to a peer given the peer's supported capabilities.
     */
    private static boolean shouldTransmitPayloadToPeer(Capabilities peerCapabilities, NetworkPayload payload) {

        // Sanity check to ensure this isn't used outside P2PDataStorage
        if (!(payload instanceof ProtectedStoragePayload || payload instanceof PersistableNetworkPayload))
            return false;

        // If the payload doesn't have a required capability, we should transmit it
        if (!(payload instanceof CapabilityRequiringPayload))
            return true;

        // Otherwise, only transmit the Payload if the peer supports all capabilities required by the payload
        boolean shouldTransmit = peerCapabilities.containsAll(((CapabilityRequiringPayload) payload).getRequiredCapabilities());

        if (!shouldTransmit) {
            log.debug("We do not send the message to the peer because they do not support the required capability for that message type.\n" +
                    "storagePayload is: " + Utilities.toTruncatedString(payload));
        }

        return shouldTransmit;
    }

    /**
     * Processes a GetDataResponse message and updates internal state. Does not broadcast updates to the P2P network
     * or domain listeners.
     */
    public void processGetDataResponse(GetDataResponse getDataResponse, NodeAddress sender) {
        Set<ProtectedStorageEntry> protectedStorageEntries = getDataResponse.getDataSet();
        Set<PersistableNetworkPayload> persistableNetworkPayloadSet = getDataResponse.getPersistableNetworkPayloadSet();
        long ts = System.currentTimeMillis();
        protectedStorageEntries.forEach(protectedStorageEntry -> {
            // We rebroadcast high priority data after a delay for better resilience
            if (protectedStorageEntry.getProtectedStoragePayload().getGetDataResponsePriority() == GetDataResponsePriority.HIGH) {
                UserThread.runAfter(() -> {
                    log.info("Rebroadcast {}", protectedStorageEntry.getProtectedStoragePayload().getClass().getSimpleName());
                    broadcaster.broadcast(new AddDataMessage(protectedStorageEntry), sender, null);
                }, 60);
            }

            // We don't broadcast here (last param) as we are only connected to the seed node and would be pointless
            addProtectedStorageEntry(protectedStorageEntry, sender, null, false);

        });
        log.info("Processing {} protectedStorageEntries took {} ms.", protectedStorageEntries.size(), this.clock.millis() - ts);

        ts = this.clock.millis();
        persistableNetworkPayloadSet.forEach(e -> {
            if (e instanceof ProcessOncePersistableNetworkPayload) {
                // We use an optimized method as many checks are not required in that case to avoid
                // performance issues.
                // Processing 82645 items took now 61 ms compared to earlier version where it took ages (> 2min).
                // Usually we only get about a few hundred or max. a few 1000 items. 82645 is all
                // trade stats and all account age witness data.

                // We only apply it once from first response
                if (!initialRequestApplied || getDataResponse.isWasTruncated()) {
                    addPersistableNetworkPayloadFromInitialRequest(e);
                }
            } else {
                // We don't broadcast here as we are only connected to the seed node and would be pointless
                addPersistableNetworkPayload(e, sender, false, false, false);
            }
        });
        log.info("Processing {} persistableNetworkPayloads took {} ms.",
                persistableNetworkPayloadSet.size(), this.clock.millis() - ts);

        // We only process PersistableNetworkPayloads implementing ProcessOncePersistableNetworkPayload once. It can cause performance
        // issues and since the data is rarely out of sync it is not worth it to apply them from multiple peers during
        // startup.
        initialRequestApplied = true;
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void shutDown() {
        if (removeExpiredEntriesTimer != null)
            removeExpiredEntriesTimer.stop();
    }

    @VisibleForTesting
    void removeExpiredEntries() {
        synchronized (map) {
            // The moment when an object becomes expired will not be synchronous in the network and we could
            // get add network_messages after the object has expired. To avoid repeated additions of already expired
            // object when we get it sent from new peers, we don’t remove the sequence number from the map.
            // That way an ADD message for an already expired data will fail because the sequence number
            // is equal and not larger as expected.
            ArrayList<Map.Entry<ByteArray, ProtectedStorageEntry>> toRemoveList = map.entrySet().stream()
                    .filter(entry -> entry.getValue().isExpired(this.clock))
                    .collect(Collectors.toCollection(ArrayList::new));

            // Batch processing can cause performance issues, so do all of the removes first, then update the listeners
            // to let them know about the removes.
            if (log.isDebugEnabled()) {
                toRemoveList.forEach(toRemoveItem -> {
                    log.debug("We found an expired data entry. We remove the protectedData:\n\t{}",
                            Utilities.toTruncatedString(toRemoveItem.getValue()));
                });
            }
            removeFromMapAndDataStore(toRemoveList);

            synchronized (sequenceNumberMap.getMap()) {
                if (sequenceNumberMap.size() > this.maxSequenceNumberMapSizeBeforePurge) {
                    sequenceNumberMap.setMap(getPurgedSequenceNumberMap(sequenceNumberMap.getMap()));
                    requestPersistence();
                }
            }
        }
    }

    public void onBootstrapped() {
        removeExpiredEntriesTimer = UserThread.runPeriodically(this::removeExpiredEntries, CHECK_TTL_INTERVAL_SEC);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // MessageListener implementation
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void onMessage(NetworkEnvelope networkEnvelope, Connection connection) {
        if (networkEnvelope instanceof BroadcastMessage) {
            connection.getPeersNodeAddressOptional().ifPresent(peersNodeAddress -> {
                if (networkEnvelope instanceof AddDataMessage) {
                    addProtectedStorageEntry(((AddDataMessage) networkEnvelope).getProtectedStorageEntry(), peersNodeAddress, null, true);
                } else if (networkEnvelope instanceof RemoveDataMessage) {
                    remove(((RemoveDataMessage) networkEnvelope).getProtectedStorageEntry(), peersNodeAddress);
                } else if (networkEnvelope instanceof RemoveMailboxDataMessage) {
                    remove(((RemoveMailboxDataMessage) networkEnvelope).getProtectedMailboxStorageEntry(), peersNodeAddress);
                } else if (networkEnvelope instanceof RefreshOfferMessage) {
                    refreshTTL((RefreshOfferMessage) networkEnvelope, peersNodeAddress);
                } else if (networkEnvelope instanceof AddPersistableNetworkPayloadMessage) {
                    addPersistableNetworkPayload(((AddPersistableNetworkPayloadMessage) networkEnvelope).getPersistableNetworkPayload(),
                            peersNodeAddress, true, false, true);
                }
            });
        }
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // ConnectionListener implementation
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void onConnection(Connection connection) {
    }

    @Override
    public void onDisconnect(CloseConnectionReason closeConnectionReason, Connection connection) {
        if (closeConnectionReason.isIntended)
            return;

        if (!connection.getPeersNodeAddressOptional().isPresent())
            return;

        NodeAddress peersNodeAddress = connection.getPeersNodeAddressOptional().get();

        // Backdate all the eligible payloads based on the node that disconnected
        synchronized (map) {
            map.values().stream()
            .filter(protectedStorageEntry -> protectedStorageEntry.getProtectedStoragePayload() instanceof RequiresOwnerIsOnlinePayload)
            .filter(protectedStorageEntry -> ((RequiresOwnerIsOnlinePayload) protectedStorageEntry.getProtectedStoragePayload()).getOwnerNodeAddress().equals(peersNodeAddress))
            .forEach(protectedStorageEntry -> {
                // We only set the data back by half of the TTL and remove the data only if is has
                // expired after that back dating.
                // We might get connection drops which are not caused by the node going offline, so
                // we give more tolerance with that approach, giving the node the chance to
                // refresh the TTL with a refresh message.
                // We observed those issues during stress tests, but it might have been caused by the
                // test set up (many nodes/connections over 1 router)
                // TODO investigate what causes the disconnections.
                // Usually the are: SOCKET_TIMEOUT ,TERMINATED (EOFException)
                log.debug("Backdating {} due to closeConnectionReason={}", protectedStorageEntry, closeConnectionReason);
                protectedStorageEntry.backDate();
            });
        }
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Client API
    ///////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Adds a PersistableNetworkPayload to the local P2P data storage. If it does not already exist locally, it will
     * be broadcast to the P2P network.
     * @param payload PersistableNetworkPayload to add to the network
     * @param sender local NodeAddress, if available
     * @param allowReBroadcast <code>true</code> if the PersistableNetworkPayload should be rebroadcast even if it
     *                         already exists locally
     * @return <code>true</code> if the PersistableNetworkPayload passes all validation and exists in the P2PDataStore
     *         on completion
     */
    public boolean addPersistableNetworkPayload(PersistableNetworkPayload payload,
                                                @Nullable NodeAddress sender,
                                                boolean allowReBroadcast) {
        return addPersistableNetworkPayload(
                payload, sender, true, allowReBroadcast, false);
    }

    private boolean addPersistableNetworkPayload(PersistableNetworkPayload payload,
                                                 @Nullable NodeAddress sender,
                                                 boolean allowBroadcast,
                                                 boolean reBroadcast,
                                                 boolean checkDate) {
        log.debug("addPersistableNetworkPayload payload={}", payload);

        // Payload hash size does not match expectation for that type of message.
        if (!payload.verifyHashSize()) {
            log.warn("addPersistableNetworkPayload failed due to unexpected hash size");
            return false;
        }

        ByteArray hashAsByteArray = new ByteArray(payload.getHash());
        boolean payloadHashAlreadyInStore = appendOnlyDataStoreService.getMap(payload).containsKey(hashAsByteArray);

        // Store already knows about this payload. Ignore it unless the caller specifically requests a republish.
        if (payloadHashAlreadyInStore && !reBroadcast) {
            log.debug("addPersistableNetworkPayload failed due to duplicate payload");
            return false;
        }

        // DateTolerantPayloads are only checked for tolerance from the onMessage handler (checkDate == true). If not in
        // tolerance, ignore it.
        if (checkDate && payload instanceof DateTolerantPayload && !((DateTolerantPayload) payload).isDateInTolerance((clock))) {
            log.warn("addPersistableNetworkPayload failed due to payload time outside tolerance.\n" +
                    "Payload={}; now={}", payload.toString(), new Date());
            return false;
        }

        // Add the payload and publish the state update to the appendOnlyDataStoreListeners
        boolean wasAdded = false;
        if (!payloadHashAlreadyInStore) {
            wasAdded = appendOnlyDataStoreService.put(hashAsByteArray, payload);
            if (wasAdded) {
                appendOnlyDataStoreListeners.forEach(e -> e.onAdded(payload));
            }
        }

        // Broadcast the payload if requested by caller
        if (allowBroadcast && wasAdded)
            broadcaster.broadcast(new AddPersistableNetworkPayloadMessage(payload), sender);

        return true;
    }

    // When we receive initial data we skip several checks to improve performance. We requested only missing entries so we
    // do not need to check again if the item is contained in the map, which is a bit slow as the map can be very large.
    // Overwriting an entry would be also no issue. We also skip notifying listeners as we get called before the domain
    // is ready so no listeners are set anyway. We might get called twice from a redundant call later, so listeners
    // might be added then but as we have the data already added calling them would be irrelevant as well.
    private void addPersistableNetworkPayloadFromInitialRequest(PersistableNetworkPayload payload) {
        byte[] hash = payload.getHash();
        if (payload.verifyHashSize()) {
            ByteArray hashAsByteArray = new ByteArray(hash);
            appendOnlyDataStoreService.put(hashAsByteArray, payload);
        } else {
            log.warn("We got a hash exceeding our permitted size");
        }
    }

    public boolean addProtectedStorageEntry(ProtectedStorageEntry protectedStorageEntry,
                                            @Nullable NodeAddress sender,
                                            @Nullable BroadcastHandler.Listener listener) {
        return addProtectedStorageEntry(protectedStorageEntry, sender, listener, true);
    }

    /**
     * Adds a ProtectedStorageEntry to the local P2P data storage and broadcast if all checks have been successful.
     *
     * @param protectedStorageEntry ProtectedStorageEntry to add to the network
     * @param sender Senders nodeAddress, if available
     * @param listener optional listener that can be used to receive events on broadcast
     * @param allowBroadcast Flag to allow broadcast
     * @return <code>true</code> if the ProtectedStorageEntry was added to the local P2P data storage
     */
    private boolean addProtectedStorageEntry(ProtectedStorageEntry protectedStorageEntry,
                                             @Nullable NodeAddress sender,
                                             @Nullable BroadcastHandler.Listener listener,
                                             boolean allowBroadcast) {
        synchronized (map) {
            ProtectedStoragePayload protectedStoragePayload = protectedStorageEntry.getProtectedStoragePayload();
            ByteArray hashOfPayload = get32ByteHashAsByteArray(protectedStoragePayload);

            //log.trace("## call addProtectedStorageEntry hash={}, map={}", hashOfPayload, printMap());

            // We do that check early as it is a very common case for returning, so we return early
            // If we have seen a more recent operation for this payload and we have a payload locally, ignore it
            ProtectedStorageEntry storedEntry = map.get(hashOfPayload);
            if (storedEntry != null && !hasSequenceNrIncreased(protectedStorageEntry.getSequenceNumber(), hashOfPayload)) {
                log.trace("## hasSequenceNrIncreased is false. hash={}", hashOfPayload);
                return false;
            }

            if (hasAlreadyRemovedAddOncePayload(protectedStoragePayload, hashOfPayload)) {
                log.trace("## We have already removed that AddOncePayload by a previous removeDataMessage. " +
                        "We ignore that message. ProtectedStoragePayload: {}", protectedStoragePayload.toString());
                return false;
            }

            // To avoid that expired data get stored and broadcast we check for expire date.
            if (protectedStorageEntry.isExpired(clock)) {
                String peer = sender != null ? sender.getFullAddress() : "sender is null";
                log.trace("## We received an expired protectedStorageEntry from peer {}. ProtectedStoragePayload={}",
                        peer, protectedStorageEntry.getProtectedStoragePayload().getClass().getSimpleName());
                return false;
            }

            // We want to allow add operations for equal sequence numbers if we don't have the payload locally. This is
            // the case for non-persistent Payloads that need to be reconstructed from peer and seed nodes each startup.
            MapValue sequenceNumberMapValue = sequenceNumberMap.get(hashOfPayload);
            if (sequenceNumberMapValue != null &&
                    protectedStorageEntry.getSequenceNumber() < sequenceNumberMapValue.sequenceNr) {
                log.trace("## sequenceNr too low hash={}", hashOfPayload);
                return false;
            }

            // Verify the ProtectedStorageEntry is well formed and valid for the add operation
            if (!protectedStorageEntry.isValidForAddOperation()) {
                log.trace("## !isValidForAddOperation hash={}", hashOfPayload);
                return false;
            }

            // If we have already seen an Entry with the same hash, verify the metadata is equal
            if (storedEntry != null && !protectedStorageEntry.matchesRelevantPubKey(storedEntry)) {
                log.trace("## !matchesRelevantPubKey hash={}", hashOfPayload);
                return false;
            }

            // Test against filterPredicate set from FilterManager
            if (filterPredicate != null &&
                    !filterPredicate.test(protectedStorageEntry.getProtectedStoragePayload())) {
                log.debug("filterPredicate test failed. hashOfPayload={}", hashOfPayload);
                return false;
            }

            // This is an updated entry. Record it and signal listeners.
            map.put(hashOfPayload, protectedStorageEntry);
            hashMapChangedListeners.forEach(e -> e.onAdded(Collections.singletonList(protectedStorageEntry)));

            // Record the updated sequence number and persist it. Higher delay so we can batch more items.
            sequenceNumberMap.put(hashOfPayload, new MapValue(protectedStorageEntry.getSequenceNumber(), this.clock.millis()));
            requestPersistence();

            //log.trace("## ProtectedStorageEntry added to map. hash={}, map={}", hashOfPayload, printMap());

            // Optionally, broadcast the add/update depending on the calling environment
            if (allowBroadcast) {
                broadcaster.broadcast(new AddDataMessage(protectedStorageEntry), sender, listener);
                log.trace("## broadcasted ProtectedStorageEntry. hash={}", hashOfPayload);
            }
            // Persist ProtectedStorageEntries carrying PersistablePayload payloads
            if (protectedStoragePayload instanceof PersistablePayload)
                protectedDataStoreService.put(hashOfPayload, protectedStorageEntry);

            return true;
        }
    }

    /**
     * We do not do all checks as it is used for republishing existing mailbox messages from seed nodes which
     * only got stored if they had been valid when we received them.
     *
     * @param protectedMailboxStorageEntry ProtectedMailboxStorageEntry to add to the network
     * @param sender Senders nodeAddress, if available
     * @param listener optional listener that can be used to receive events on broadcast
     */
    public void republishExistingProtectedMailboxStorageEntry(ProtectedMailboxStorageEntry protectedMailboxStorageEntry,
                                                              @Nullable NodeAddress sender,
                                                              @Nullable BroadcastHandler.Listener listener) {
        ProtectedStoragePayload protectedStoragePayload = protectedMailboxStorageEntry.getProtectedStoragePayload();
        ByteArray hashOfPayload = get32ByteHashAsByteArray(protectedStoragePayload);

        //log.trace("## call republishProtectedStorageEntry hash={}, map={}", hashOfPayload, printMap());

        if (hasAlreadyRemovedAddOncePayload(protectedStoragePayload, hashOfPayload)) {
            log.trace("## We have already removed that AddOncePayload by a previous removeDataMessage. " +
                    "We ignore that message. ProtectedStoragePayload: {}", protectedStoragePayload.toString());
            return;
        }

        broadcaster.broadcast(new AddDataMessage(protectedMailboxStorageEntry), sender, listener);
        log.trace("## broadcasted ProtectedStorageEntry. hash={}", hashOfPayload);
    }

    public boolean hasAlreadyRemovedAddOncePayload(ProtectedStoragePayload protectedStoragePayload,
                                                   ByteArray hashOfPayload) {
        return protectedStoragePayload instanceof AddOncePayload && removedPayloadsService.wasRemoved(hashOfPayload);
    }

    /**
     * Updates a local RefreshOffer with TTL changes and broadcasts those changes to the network
     *
     * @param refreshTTLMessage refreshTTLMessage containing the update
     * @param sender local NodeAddress, if available
     * @return <code>true</code> if the RefreshOffer was successfully updated and changes broadcast
     */
    public boolean refreshTTL(RefreshOfferMessage refreshTTLMessage,
                              @Nullable NodeAddress sender) {
        synchronized (map) {
            try {
                ByteArray hashOfPayload = new ByteArray(refreshTTLMessage.getHashOfPayload());
                ProtectedStorageEntry storedData = map.get(hashOfPayload);

                if (storedData == null) {
                    log.debug("We don't have data for that refresh message in our map. That is expected if we missed the data publishing.");

                    return false;
                }

                ProtectedStorageEntry storedEntry = map.get(hashOfPayload);
                ProtectedStorageEntry updatedEntry = new ProtectedStorageEntry(
                        storedEntry.getProtectedStoragePayload(),
                        storedEntry.getOwnerPubKey(),
                        refreshTTLMessage.getSequenceNumber(),
                        refreshTTLMessage.getSignature(),
                        this.clock);


                // If we have seen a more recent operation for this payload, we ignore the current one
                if (!hasSequenceNrIncreased(updatedEntry.getSequenceNumber(), hashOfPayload))
                    return false;

                // Verify the updated ProtectedStorageEntry is well formed and valid for update
                if (!updatedEntry.isValidForAddOperation())
                    return false;

                // Update the hash map with the updated entry
                map.put(hashOfPayload, updatedEntry);

                // Record the latest sequence number and persist it
                sequenceNumberMap.put(hashOfPayload, new MapValue(updatedEntry.getSequenceNumber(), this.clock.millis()));
                requestPersistence();

                // Always broadcast refreshes
                broadcaster.broadcast(refreshTTLMessage, sender);

            } catch (IllegalArgumentException e) {
                log.error("refreshTTL failed, missing data: {}\n", e.toString(), e);
                return false;
            }
            return true;
        }
    }

    /**
     * Removes a ProtectedStorageEntry from the local P2P data storage. If it is successful, it will broadcast that
     * change to the P2P network.
     *
     * @param protectedStorageEntry ProtectedStorageEntry to add to the network
     * @param sender local NodeAddress, if available
     * @return <code>true</code> if the ProtectedStorageEntry was removed from the local P2P data storage and broadcast
     */
    public boolean remove(ProtectedStorageEntry protectedStorageEntry,
                          @Nullable NodeAddress sender) {
        synchronized (map) {
            ProtectedStoragePayload protectedStoragePayload = protectedStorageEntry.getProtectedStoragePayload();
            ByteArray hashOfPayload = get32ByteHashAsByteArray(protectedStoragePayload);

            // If we have seen a more recent operation for this payload, ignore this one
            if (!hasSequenceNrIncreased(protectedStorageEntry.getSequenceNumber(), hashOfPayload))
                return false;

            // Verify the ProtectedStorageEntry is well formed and valid for the remove operation
            if (!protectedStorageEntry.isValidForRemoveOperation())
                return false;

            // If we have already seen an Entry with the same hash, verify the metadata is the same
            ProtectedStorageEntry storedEntry = map.get(hashOfPayload);
            if (storedEntry != null && !protectedStorageEntry.matchesRelevantPubKey(storedEntry))
                return false;

            // Record the latest sequence number and persist it
            sequenceNumberMap.put(hashOfPayload, new MapValue(protectedStorageEntry.getSequenceNumber(), this.clock.millis()));
            requestPersistence();

            // Update that we have seen this AddOncePayload so the next time it is seen it fails verification
            if (protectedStoragePayload instanceof AddOncePayload) {
                removedPayloadsService.addHash(hashOfPayload);
            }

            if (storedEntry != null) {
                // Valid remove entry, do the remove and signal listeners
                removeFromMapAndDataStore(protectedStorageEntry, hashOfPayload);
            } /* else {
                // This means the RemoveData or RemoveMailboxData was seen prior to the AddData. We have already updated
                // the SequenceNumberMap appropriately so the stale Add will not pass validation, but we still want to
                // broadcast the remove to peers so they can update their state appropriately
            } */
            printData("after remove");

            if (protectedStorageEntry instanceof ProtectedMailboxStorageEntry) {
                broadcaster.broadcast(new RemoveMailboxDataMessage((ProtectedMailboxStorageEntry) protectedStorageEntry), sender);
            } else {
                broadcaster.broadcast(new RemoveDataMessage(protectedStorageEntry), sender);
            }

            return true;
        }
    }

    public ProtectedStorageEntry getProtectedStorageEntry(ProtectedStoragePayload protectedStoragePayload,
                                                          KeyPair ownerStoragePubKey)
            throws CryptoException {
        ByteArray hashOfData = get32ByteHashAsByteArray(protectedStoragePayload);
        int sequenceNumber;
        if (sequenceNumberMap.containsKey(hashOfData))
            sequenceNumber = sequenceNumberMap.get(hashOfData).sequenceNr + 1;
        else
            sequenceNumber = 1;

        byte[] hashOfDataAndSeqNr = P2PDataStorage.get32ByteHash(new DataAndSeqNrPair(protectedStoragePayload, sequenceNumber));
        byte[] signature = Sig.sign(ownerStoragePubKey.getPrivate(), hashOfDataAndSeqNr);
        return new ProtectedStorageEntry(protectedStoragePayload, ownerStoragePubKey.getPublic(), sequenceNumber, signature, this.clock);
    }

    public RefreshOfferMessage getRefreshTTLMessage(ProtectedStoragePayload protectedStoragePayload,
                                                    KeyPair ownerStoragePubKey)
            throws CryptoException {
        ByteArray hashOfPayload = get32ByteHashAsByteArray(protectedStoragePayload);
        int sequenceNumber;
        if (sequenceNumberMap.containsKey(hashOfPayload))
            sequenceNumber = sequenceNumberMap.get(hashOfPayload).sequenceNr + 1;
        else
            sequenceNumber = 1;

        byte[] hashOfDataAndSeqNr = P2PDataStorage.get32ByteHash(new DataAndSeqNrPair(protectedStoragePayload, sequenceNumber));
        byte[] signature = Sig.sign(ownerStoragePubKey.getPrivate(), hashOfDataAndSeqNr);
        return new RefreshOfferMessage(hashOfDataAndSeqNr, signature, hashOfPayload.bytes, sequenceNumber);
    }

    public ProtectedMailboxStorageEntry getMailboxDataWithSignedSeqNr(MailboxStoragePayload expirableMailboxStoragePayload,
                                                                      KeyPair storageSignaturePubKey,
                                                                      PublicKey receiversPublicKey)
            throws CryptoException {
        ByteArray hashOfData = get32ByteHashAsByteArray(expirableMailboxStoragePayload);
        int sequenceNumber;
        if (sequenceNumberMap.containsKey(hashOfData))
            sequenceNumber = sequenceNumberMap.get(hashOfData).sequenceNr + 1;
        else
            sequenceNumber = 1;

        byte[] hashOfDataAndSeqNr = P2PDataStorage.get32ByteHash(new DataAndSeqNrPair(expirableMailboxStoragePayload, sequenceNumber));
        byte[] signature = Sig.sign(storageSignaturePubKey.getPrivate(), hashOfDataAndSeqNr);
        return new ProtectedMailboxStorageEntry(expirableMailboxStoragePayload,
                storageSignaturePubKey.getPublic(), sequenceNumber, signature, receiversPublicKey, this.clock);
    }

    public void addHashMapChangedListener(HashMapChangedListener hashMapChangedListener) {
        hashMapChangedListeners.add(hashMapChangedListener);
    }

    public void removeHashMapChangedListener(HashMapChangedListener hashMapChangedListener) {
        hashMapChangedListeners.remove(hashMapChangedListener);
    }

    public void addAppendOnlyDataStoreListener(AppendOnlyDataStoreListener listener) {
        appendOnlyDataStoreListeners.add(listener);
    }

    public void removeAppendOnlyDataStoreListener(AppendOnlyDataStoreListener listener) {
        appendOnlyDataStoreListeners.remove(listener);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Private
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void removeFromMapAndDataStore(ProtectedStorageEntry protectedStorageEntry, ByteArray hashOfPayload) {
        removeFromMapAndDataStore(Collections.singletonList(Maps.immutableEntry(hashOfPayload, protectedStorageEntry)));
    }

    private void removeFromMapAndDataStore(Collection<Map.Entry<ByteArray, ProtectedStorageEntry>> entriesToRemove) {
        synchronized (map) {
            if (entriesToRemove.isEmpty())
                return;

            List<ProtectedStorageEntry> removedProtectedStorageEntries = new ArrayList<>(entriesToRemove.size());
            entriesToRemove.forEach(entry -> {
                ByteArray hashOfPayload = entry.getKey();
                ProtectedStorageEntry protectedStorageEntry = entry.getValue();

                //log.trace("## removeFromMapAndDataStore: hashOfPayload={}, map before remove={}", hashOfPayload, printMap());
                map.remove(hashOfPayload);
                //log.trace("## removeFromMapAndDataStore: map after remove={}", printMap());

                // We inform listeners even the entry was not found in our map
                removedProtectedStorageEntries.add(protectedStorageEntry);

                ProtectedStoragePayload protectedStoragePayload = protectedStorageEntry.getProtectedStoragePayload();
                if (protectedStoragePayload instanceof PersistablePayload) {
                    ProtectedStorageEntry previous = protectedDataStoreService.remove(hashOfPayload, protectedStorageEntry);
                    if (previous == null)
                        log.warn("We cannot remove the protectedStorageEntry from the protectedDataStoreService as it does not exist.");
                }
            });

            hashMapChangedListeners.forEach(e -> e.onRemoved(removedProtectedStorageEntries));
        }
    }

    private boolean hasSequenceNrIncreased(int newSequenceNumber, ByteArray hashOfData) {
        if (sequenceNumberMap.containsKey(hashOfData)) {
            int storedSequenceNumber = sequenceNumberMap.get(hashOfData).sequenceNr;
            if (newSequenceNumber > storedSequenceNumber) {
                /*log.debug("Sequence number has increased (>). sequenceNumber = "
                        + newSequenceNumber + " / storedSequenceNumber=" + storedSequenceNumber + " / hashOfData=" + hashOfData.toString());*/
                return true;
            } else if (newSequenceNumber == storedSequenceNumber) {
                if (newSequenceNumber == 0) {
                    log.debug("Sequence number is equal to the stored one and both are 0." +
                            "That is expected for network_messages which never got updated (mailbox msg).");
                } else {
                    log.debug("Sequence number is equal to the stored one. sequenceNumber = {} / storedSequenceNumber={}",
                            newSequenceNumber, storedSequenceNumber);
                }
                return false;
            } else {
                log.debug("Sequence number is invalid. sequenceNumber = {} / storedSequenceNumber={} " +
                                "That can happen if the data owner gets an old delayed data storage message.",
                        newSequenceNumber, storedSequenceNumber);
                return false;
            }
        } else {
            return true;
        }
    }

    private void requestPersistence() {
        persistenceManager.requestPersistence();
    }

    public static ByteArray get32ByteHashAsByteArray(NetworkPayload data) {
        return new ByteArray(P2PDataStorage.get32ByteHash(data));
    }

    // Get a new map with entries older than PURGE_AGE_DAYS purged from the given map.
    private Map<ByteArray, MapValue> getPurgedSequenceNumberMap(Map<ByteArray, MapValue> persisted) {
        Map<ByteArray, MapValue> purged = new HashMap<>();
        long maxAgeTs = this.clock.millis() - TimeUnit.DAYS.toMillis(PURGE_AGE_DAYS);
        persisted.forEach((key, value) -> {
            if (value.timeStamp > maxAgeTs)
                purged.put(key, value);
        });
        return purged;
    }

    private void printData(String info) {
        if (log.isTraceEnabled()) {
            StringBuilder sb = new StringBuilder("\n\n------------------------------------------------------------\n");
            sb.append("Data set ").append(info).append(" operation");
            // We print the items sorted by hash with the payload class name and id
            List<Tuple2<String, ProtectedStorageEntry>> tempList = map.values().stream()
                    .map(e -> new Tuple2<>(org.bitcoinj.core.Utils.HEX.encode(get32ByteHashAsByteArray(e.getProtectedStoragePayload()).bytes), e))
                    .sorted(Comparator.comparing(o -> o.first))
                    .collect(Collectors.toList());
            tempList.forEach(e -> {
                ProtectedStorageEntry storageEntry = e.second;
                ProtectedStoragePayload protectedStoragePayload = storageEntry.getProtectedStoragePayload();
                MapValue mapValue = sequenceNumberMap.get(get32ByteHashAsByteArray(protectedStoragePayload));
                sb.append("\n")
                        .append("Hash=")
                        .append(e.first)
                        .append("; Class=")
                        .append(protectedStoragePayload.getClass().getSimpleName())
                        .append("; SequenceNumbers (Object/Stored)=")
                        .append(storageEntry.getSequenceNumber())
                        .append(" / ")
                        .append(mapValue != null ? mapValue.sequenceNr : "null")
                        .append("; TimeStamp (Object/Stored)=")
                        .append(storageEntry.getCreationTimeStamp())
                        .append(" / ")
                        .append(mapValue != null ? mapValue.timeStamp : "null")
                        .append("; Payload=")
                        .append(Utilities.toTruncatedString(protectedStoragePayload));
            });
            sb.append("\n------------------------------------------------------------\n");
            log.debug(sb.toString());
            //log.debug("Data set " + info + " operation: size=" + map.values().size());
        }
    }

    private String printMap() {
        return Arrays.toString(map.entrySet().stream().map(e -> Hex.encode(e.getKey().bytes) + ": " +
                e.getValue().getProtectedStoragePayload().getClass().getSimpleName()).toArray());
    }

    private String printPersistableNetworkPayloadMap(Map<ByteArray, PersistableNetworkPayload> map) {
        return Arrays.toString(map.entrySet().stream().map(e -> Hex.encode(e.getKey().bytes) + ": " +
                e.getValue().getClass().getSimpleName()).toArray());
    }

    /**
     * @param data Network payload
     * @return Hash of data
     */
    public static byte[] get32ByteHash(NetworkPayload data) {
        return Hash.getSha256Hash(data.toProtoMessage().toByteArray());
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Static class
    ///////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Used as container for calculating cryptographic hash of data and sequenceNumber.
     */
    @EqualsAndHashCode
    @ToString
    public static final class DataAndSeqNrPair implements NetworkPayload {
        // data are only used for calculating cryptographic hash from both values so they are kept private
        private final ProtectedStoragePayload protectedStoragePayload;
        private final int sequenceNumber;

        public DataAndSeqNrPair(ProtectedStoragePayload protectedStoragePayload, int sequenceNumber) {
            this.protectedStoragePayload = protectedStoragePayload;
            this.sequenceNumber = sequenceNumber;
        }

        // Used only for calculating hash of byte array from PB object
        @Override
        public com.google.protobuf.Message toProtoMessage() {
            return protobuf.DataAndSeqNrPair.newBuilder()
                    .setPayload((protobuf.StoragePayload) protectedStoragePayload.toProtoMessage())
                    .setSequenceNumber(sequenceNumber)
                    .build();
        }
    }

    /**
     * Used as key object in map for cryptographic hash of stored data as byte[] as primitive data type cannot be
     * used as key
     */
    @EqualsAndHashCode
    public static final class ByteArray implements PersistablePayload {
        // That object is saved to disc. We need to take care of changes to not break deserialization.
        public final byte[] bytes;

        public ByteArray(byte[] bytes) {
            this.bytes = bytes;
            verifyBytesNotEmpty();
        }

        public void verifyBytesNotEmpty() {
            if (this.bytes == null)
                throw new IllegalArgumentException("Cannot create P2PDataStorage.ByteArray with null byte[] array argument.");

            if (this.bytes.length == 0)
                throw new IllegalArgumentException("Cannot create P2PDataStorage.ByteArray with empty byte[] array argument.");
        }

        @Override
        public String toString() {
            return "ByteArray{" +
                    "bytes as Hex=" + Hex.encode(bytes) +
                    '}';
        }

        ///////////////////////////////////////////////////////////////////////////////////////////
        // Protobuffer
        ///////////////////////////////////////////////////////////////////////////////////////////

        @Override
        public protobuf.ByteArray toProtoMessage() {
            return protobuf.ByteArray.newBuilder().setBytes(ByteString.copyFrom(bytes)).build();
        }

        public static ByteArray fromProto(protobuf.ByteArray proto) {
            return new ByteArray(proto.getBytes().toByteArray());
        }

        ///////////////////////////////////////////////////////////////////////////////////////////
        // Util
        ///////////////////////////////////////////////////////////////////////////////////////////

        public String getHex() {
            return Utilities.encodeToHex(bytes);
        }

        public static Set<P2PDataStorage.ByteArray> convertBytesSetToByteArraySet(Set<byte[]> set) {
            return set != null ?
                    set.stream()
                            .map(P2PDataStorage.ByteArray::new)
                            .collect(Collectors.toSet())
                    : new HashSet<>();
        }
    }

    /**
     * Used as value in map
     */
    @EqualsAndHashCode
    @ToString
    public static final class MapValue implements PersistablePayload {
        // That object is saved to disc. We need to take care of changes to not break deserialization.
        final public int sequenceNr;
        final public long timeStamp;

        MapValue(int sequenceNr, long timeStamp) {
            this.sequenceNr = sequenceNr;
            this.timeStamp = timeStamp;
        }

        @Override
        public protobuf.MapValue toProtoMessage() {
            return protobuf.MapValue.newBuilder().setSequenceNr(sequenceNr).setTimeStamp(timeStamp).build();
        }

        public static MapValue fromProto(protobuf.MapValue proto) {
            return new MapValue(proto.getSequenceNr(), proto.getTimeStamp());
        }
    }
}
