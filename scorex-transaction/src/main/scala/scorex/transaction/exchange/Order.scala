package scorex.transaction.exchange

import com.google.common.primitives.Longs
import scorex.account.{Account, PrivateKeyAccount, PublicKeyAccount}
import scorex.crypto.EllipticCurveImpl
import scorex.serialization.{BytesSerializable, Deser}
import scorex.utils.{ByteArray, NTP}

import scala.util.Try

case class Order(sender: PublicKeyAccount, matcher: PublicKeyAccount, spendAssetId: Array[Byte],
                 receiveAssetId: Array[Byte], price: Long, amount: Long, maxTimestamp: Long, matcherFee: Long,
                 signature: Array[Byte]) extends BytesSerializable {

  import Order._

  /**
    * In what assets is price
    */
  lazy val priceAssetId: Array[Byte] = if (ByteArray.compare(spendAssetId, receiveAssetId) > 0) receiveAssetId
  else spendAssetId

  /**
    * In what assets is amount
    */
  lazy val amountAsset: Array[Byte] = if (priceAssetId sameElements spendAssetId) receiveAssetId
  else spendAssetId


  def isValid: Boolean = {
    amount > 0 && price > 0 && maxTimestamp - NTP.correctedTime() < MaxLiveTime &&
      NTP.correctedTime() < maxTimestamp && EllipticCurveImpl.verify(signature, toSign, sender.publicKey)
  }

  lazy val toSign: Array[Byte] = sender.publicKey ++ matcher.publicKey ++ spendAssetId ++ receiveAssetId ++
    Longs.toByteArray(price) ++ Longs.toByteArray(amount) ++ Longs.toByteArray(maxTimestamp) ++
    Longs.toByteArray(matcherFee)


  override def bytes: Array[Byte] = toSign ++ signature
}

object Order extends Deser[Order] {
  val MaxLiveTime = 30 * 24 * 60 * 60 * 1000
  private val AssetIdLength = 32

  def apply(sender: PrivateKeyAccount, matcher: PublicKeyAccount, spendAssetID: Array[Byte],
            receiveAssetID: Array[Byte], price: Long, amount: Long, maxTime: Long, matcherFee: Long): Order = {
    val unsigned = Order(sender, matcher, spendAssetID, receiveAssetID, price, amount, maxTime, matcherFee, Array())
    val sig = EllipticCurveImpl.sign(sender, unsigned.toSign)
    Order(sender, matcher, spendAssetID, receiveAssetID, price, amount, maxTime, matcherFee, sig)
  }

  override def parseBytes(bytes: Array[Byte]): Try[Order] = Try {
    val sender = new PublicKeyAccount(bytes.slice(0, Account.AddressLength))
    val matcher = new PublicKeyAccount(bytes.slice(Account.AddressLength, 2 * Account.AddressLength))
    val spend = bytes.slice(2 * Account.AddressLength, 2 * Account.AddressLength + AssetIdLength)
    val receive = bytes.slice(2 * Account.AddressLength + AssetIdLength, 2 * Account.AddressLength + 2 * AssetIdLength)
    val longsStart = 2 * Account.AddressLength + 2 * AssetIdLength
    val price = Longs.fromByteArray(bytes.slice(longsStart, longsStart + 8))
    val amount = Longs.fromByteArray(bytes.slice(longsStart + 8, longsStart + 16))
    val maxTimestamp = Longs.fromByteArray(bytes.slice(longsStart + 16, longsStart + 24))
    val matcherFee = Longs.fromByteArray(bytes.slice(longsStart + 24, longsStart + 32))
    val signature = bytes.slice(longsStart + 32, bytes.length)
    Order(sender, matcher, spend, receive, price, amount, maxTimestamp, matcherFee, signature)
  }
}
