package com.wavesplatform

import java.io.File
import java.nio.ByteBuffer
import java.util

import com.typesafe.config.ConfigFactory
import com.wavesplatform.database.{Keys, LevelDBWriter}
import com.wavesplatform.db.openDB
import com.wavesplatform.settings.{WavesSettings, loadConfig}
import com.wavesplatform.state.ByteStr
import org.slf4j.bridge.SLF4JBridgeHandler
import scorex.account.{Address, AddressScheme}
import com.wavesplatform.utils.Base58
import scorex.utils.ScorexLogging

import scala.collection.JavaConverters._
import scala.util.Try
import scala.collection.JavaConverters._

object Explorer extends ScorexLogging {
  case class Stats(entryCount: Long, totalKeySize: Long, totalValueSize: Long)

  private val keys = Array(
    "version",
    "height",
    "score",
    "block-at-height",
    "height-of",
    "waves-balance-history",
    "waves-balance",
    "assets-for-address",
    "asset-balance-history",
    "asset-balance",
    "asset-info-history",
    "asset-info",
    "lease-balance-history",
    "lease-balance",
    "lease-status-history",
    "lease-status",
    "filled-volume-and-fee-history",
    "filled-volume-and-fee",
    "transaction-info",
    "address-transaction-history",
    "address-transaction-ids",
    "changed-addresses",
    "transaction-ids-at-height",
    "address-id-of-alias",
    "last-address-id",
    "address-to-id",
    "id-of-address",
    "address-script-history",
    "address-script",
    "approved-features",
    "activated-features",
    "data-key-chunk-count",
    "data-key-chunk",
    "data-history",
    "data",
    "sponsorship-history",
    "sponsorship",
    "addresses-for-waves-seq-nr",
    "addresses-for-waves",
    "addresses-for-asset-seq-nr",
    "addresses-for-asset"
  )

  def main(args: Array[String]): Unit = {
    SLF4JBridgeHandler.removeHandlersForRootLogger()
    SLF4JBridgeHandler.install()

    val configFilename = Try(args(0)).toOption.getOrElse("waves-testnet.conf")

    val settings = WavesSettings.fromConfig(loadConfig(ConfigFactory.parseFile(new File(configFilename))))
    AddressScheme.current = new AddressScheme {
      override val chainId: Byte = settings.blockchainSettings.addressSchemeCharacter.toByte
    }

    log.info(s"Data directory: ${settings.dataDirectory}")

    val db     = openDB(settings.dataDirectory, settings.levelDbCacheSize)
    val reader = new LevelDBWriter(db, settings.blockchainSettings.functionalitySettings)

    val blockchainHeight = reader.height
    log.info(s"Blockchain height is $blockchainHeight")
    try {

      val flag = args(1).toUpperCase

      flag match {
        case "O" =>
          val orderId = Base58.decode(args(2)).toOption.map(ByteStr.apply)
          if (orderId.isDefined) {
            val kVolumeAndFee = Keys.filledVolumeAndFee(blockchainHeight, orderId.get)
            val bytes1        = db.get(kVolumeAndFee.keyBytes)
            val v             = kVolumeAndFee.parse(bytes1)
            log.info(s"OrderId = ${Base58.encode(orderId.get.arr)}: Volume = ${v.volume}, Fee = ${v.fee}")

            val kVolumeAndFeeHistory = Keys.filledVolumeAndFeeHistory(orderId.get)
            val bytes2               = db.get(kVolumeAndFeeHistory.keyBytes)
            val value2               = kVolumeAndFeeHistory.parse(bytes2)
            val value2Str            = value2.mkString("[", ", ", "]")
            log.info(s"OrderId = ${Base58.encode(orderId.get.arr)}: History = $value2Str")
            value2.foreach { h =>
              val k = Keys.filledVolumeAndFee(h, orderId.get)
              val v = k.parse(db.get(k.keyBytes))
              log.info(s"\t h = $h: Volume = ${v.volume}, Fee = ${v.fee}")
            }
          } else log.error("No order ID was provided")

        case "A" =>
          val address   = Address.fromString(args(2)).right.get
          val aid       = Keys.addressId(address)
          val addressId = aid.parse(db.get(aid.keyBytes)).get
          log.info(s"Address id = $addressId")

          val kwbh = Keys.wavesBalanceHistory(addressId)
          val wbh  = kwbh.parse(db.get(kwbh.keyBytes))

          val balances = wbh.map { h =>
            val k = Keys.wavesBalance(h, addressId)
            h -> k.parse(db.get(k.keyBytes))
          }
          balances.foreach(b => log.info(s"h = ${b._1}: balance = ${b._2}"))

        case "AC" =>
          val lastAddressId = Keys.lastAddressId.parse(db.get(Keys.lastAddressId.keyBytes))
          log.info(s"Last address id: $lastAddressId")

        case "AD" =>
          val result        = new util.HashMap[Address, java.lang.Integer]()
          val lastAddressId = Keys.lastAddressId.parse(db.get(Keys.lastAddressId.keyBytes))
          for (id <- BigInt(1) to lastAddressId.getOrElse(BigInt(0))) {
            val k       = Keys.idToAddress(id)
            val address = k.parse(db.get(k.keyBytes))
            result.compute(address,
                           (_, prev) =>
                             prev match {
                               case null    => 1
                               case notNull => 1 + notNull
                           })
          }

          for ((k, v) <- result.asScala if v > 1) {
            log.info(s"$k,$v")
          }

        case "T" =>
          val address   = Address.fromString(args(2)).right.get
          val aid       = Keys.addressId(address)
          val addressId = aid.parse(db.get(aid.keyBytes)).get
          log.info(s"Address id = $addressId")
          val ktxidh = Keys.addressTransactionIds(args(3).toInt, addressId)

          for ((t, id) <- ktxidh.parse(db.get(ktxidh.keyBytes))) {
            log.info(s"$id of type $t")
          }

        case "AA" =>
          val secondaryId = args(3)

          val address   = Address.fromString(args(2)).right.get
          val asset     = ByteStr.decodeBase58(secondaryId).get
          val ai        = Keys.addressId(address)
          val addressId = ai.parse(db.get(ai.keyBytes)).get
          log.info(s"Address ID = $addressId")

          val kabh = Keys.assetBalanceHistory(addressId, asset)
          val abh  = kabh.parse(db.get(kabh.keyBytes))

          val balances = abh.map { h =>
            val k = Keys.assetBalance(h, addressId, asset)
            h -> k.parse(db.get(k.keyBytes))
          }
          balances.foreach(b => log.info(s"h = ${b._1}: balance = ${b._2}"))

        case "S" =>
          log.info("Collecting DB stats")
          val iterator = db.iterator()
          val result   = new util.HashMap[Short, Stats]
          iterator.seekToFirst()
          while (iterator.hasNext) {
            val entry     = iterator.next()
            val keyPrefix = ByteBuffer.wrap(entry.getKey).getShort
            result.compute(
              keyPrefix,
              (_, maybePrev) =>
                maybePrev match {
                  case null => Stats(1, entry.getKey.length, entry.getValue.length)
                  case prev => Stats(prev.entryCount + 1, prev.totalKeySize + entry.getKey.length, prev.totalValueSize + entry.getValue.length)
              }
            )
          }
          iterator.close()

          log.info("key-space,entry-count,total-key-size,total-value-size")
          for ((prefix, stats) <- result.asScala) {
            log.info(s"${keys(prefix)},${stats.entryCount},${stats.totalKeySize},${stats.totalValueSize}")
          }
      }
    } finally db.close()
  }
}
