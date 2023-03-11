/*
 * This file is part of Haveno.
 *
 * Haveno is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Haveno is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Haveno. If not, see <http://www.gnu.org/licenses/>.
 */

package haveno.core.offer;

import haveno.common.proto.persistable.PersistablePayload;
import java.util.List;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@EqualsAndHashCode
@Slf4j
public final class SignedOffer implements PersistablePayload {
    
    @Getter
    private final long timeStamp;
    @Getter
    private final String offerId;
    @Getter
    private final long tradeAmount;
    @Getter
    private final long penaltyAmount;
    @Getter
    private final String reserveTxHash;
    @Getter
    private final String reserveTxHex;
    @Getter
    private final List<String> reserveTxKeyImages;
    @Getter
    private final long reserveTxMinerFee;
    @Getter
    private final String arbitratorSignature;
    
    public SignedOffer(long timeStamp,
                       String offerId,
                       long tradeAmount,
                       long penaltyAmount,
                       String reserveTxHash,
                       String reserveTxHex,
                       List<String> reserveTxKeyImages,
                       long reserveTxMinerFee,
                       String arbitratorSignature) {
        this.timeStamp = timeStamp;
        this.offerId = offerId;
        this.tradeAmount = tradeAmount;
        this.penaltyAmount = penaltyAmount;
        this.reserveTxHash = reserveTxHash;
        this.reserveTxHex = reserveTxHex;
        this.reserveTxKeyImages = reserveTxKeyImages;
        this.reserveTxMinerFee = reserveTxMinerFee;
        this.arbitratorSignature = arbitratorSignature;
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public protobuf.SignedOffer toProtoMessage() {
        protobuf.SignedOffer.Builder builder = protobuf.SignedOffer.newBuilder()
                .setTimeStamp(timeStamp)
                .setOfferId(offerId)
                .setTradeAmount(tradeAmount)
                .setPenaltyAmount(penaltyAmount)
                .setReserveTxHash(reserveTxHash)
                .setReserveTxHex(reserveTxHex)
                .addAllReserveTxKeyImages(reserveTxKeyImages)
                .setReserveTxMinerFee(reserveTxMinerFee)
                .setArbitratorSignature(arbitratorSignature);
        return builder.build();
    }

    public static SignedOffer fromProto(protobuf.SignedOffer proto) {
        return new SignedOffer(proto.getTimeStamp(),
                               proto.getOfferId(),
                               proto.getTradeAmount(),
                               proto.getPenaltyAmount(),
                               proto.getReserveTxHash(),
                               proto.getReserveTxHex(),
                               proto.getReserveTxKeyImagesList(),
                               proto.getReserveTxMinerFee(),
                               proto.getArbitratorSignature());
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Getters
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public String toString() {
        return "SignedOffer{" +
                ",\n     timeStamp=" + timeStamp +
                ",\n     offerId=" + offerId +
                ",\n     tradeAmount=" + tradeAmount +
                ",\n     penaltyAmount=" + penaltyAmount +
                ",\n     reserveTxHash=" + reserveTxHash +
                ",\n     reserveTxHex=" + reserveTxHex +
                ",\n     reserveTxKeyImages=" + reserveTxKeyImages +
                ",\n     reserveTxMinerFee=" + reserveTxMinerFee +
                ",\n     arbitratorSignature=" + arbitratorSignature +
                "\n}";
    }
}
