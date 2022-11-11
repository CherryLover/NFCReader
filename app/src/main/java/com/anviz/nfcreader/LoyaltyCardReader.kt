package com.anviz.nfcreader

import android.annotation.TargetApi
import android.nfc.NfcAdapter.ReaderCallback
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.MifareClassic
import android.os.Build
import android.util.Log
import org.greenrobot.eventbus.EventBus
import java.io.IOException
import java.util.*

/**
 * Callback class, invoked when an NFC card is scanned while the device is running in reader mode.
 *
 * Reader mode can be invoked by calling NfcAdapter
 */
@TargetApi(Build.VERSION_CODES.KITKAT)
class LoyaltyCardReader() : ReaderCallback {


  companion object {
    private const val TAG = "LoyaltyCardReader"

    // AID for our loyalty card service.
    private const val SAMPLE_LOYALTY_CARD_AID = "F123422222"

    // ISO-DEP command HEADER for selecting an AID.
    // Format: [Class | Instruction | Parameter 1 | Parameter 2]
    private const val SELECT_APDU_HEADER = "00A40400"

    // "OK" status word sent in response to SELECT AID command (0x9000)
    private val SELECT_OK_SW = byteArrayOf(0x90.toByte(), 0x00.toByte())

    /**
     * Build APDU for SELECT AID command. This command indicates which service a reader is
     * interested in communicating with. See ISO 7816-4.
     *
     * @param aid Application ID (AID) to select
     * @return APDU for SELECT AID command
     */
    // aid = F123422222
    // SELECT_APDU_HEADER
    private fun buildSelectApdu(aid: String): ByteArray {
      //          00      A4            04            00            05       F123422222
      // Format: [CLASS | INSTRUCTION | PARAMETER 1 | PARAMETER 2 | LENGTH | DATA]
      return hexStringToByteArray(SELECT_APDU_HEADER + String.format("%02X", aid.length / 2) + aid)
    }

    /**
     * Utility class to convert a byte array to a hexadecimal string.
     *
     * @param bytes Bytes to convert
     * @return String, containing hexadecimal representation.
     */
    private fun byteArrayToHexString(bytes: ByteArray): String {
      val hexArray = charArrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F')
      val hexChars = CharArray(bytes.size * 2)
      var v: Int
      for (j in bytes.indices) {
        v = ((bytes[j].toInt()) and (0xFF))
        hexChars[j * 2] = hexArray[v ushr 4]
        hexChars[j * 2 + 1] = hexArray[v and 0x0F]
      }
      return String(hexChars)
    }

    /**
     * Utility class to convert a hexadecimal string to a byte string.
     *
     *
     * Behavior with input strings containing non-hexadecimal characters is undefined.
     *
     * @param s String containing hexadecimal characters to convert
     * @return Byte array generated from input
     */
    private fun hexStringToByteArray(s: String): ByteArray {
      val len = s.length
      val data = ByteArray(len / 2)
      var i = 0
      while (i < len) {
        data[i / 2] = ((Character.digit(s[i], 16) shl 4)
          + Character.digit(s[i + 1], 16)).toByte()
        i += 2
      }
      return data
    }
  }

  /**
   * Callback when a new tag is discovered by the system.
   *
   *
   * Communication with the card should take place here.
   *
   * @param tag Discovered tag
   */
  override fun onTagDiscovered(tag: Tag?) {
    if (tag == null) {
      return
    }
    log("New tag discovered tag id: " + byteArrayToHexString(tag.id))

    val isoDep: IsoDep? = IsoDep.get(tag)
    val mifareClassic: MifareClassic? = MifareClassic.get(tag)
    if (isoDep != null) {
      log("tag is isoDep")
      tryReadIsoDep(isoDep)
    } else if (mifareClassic != null) {
      log("tag is mifareClassic")
      tryReaderMifareClassic(mifareClassic)
    }
  }

  private fun tryReadIsoDep(isoDep: IsoDep) {
    try {
      // Connect to the remote NFC device
      isoDep.connect()
      // Build SELECT AID command for our loyalty card service.
      // This command tells the remote device which service we wish to communicate with.
  //        Log.i(TAG, "Requesting remote AID: " + SAMPLE_LOYALTY_CARD_AID)
      log("""Requesting remote AID: $SAMPLE_LOYALTY_CARD_AID""".trimIndent())
      val command = buildSelectApdu(SAMPLE_LOYALTY_CARD_AID)
      log("""Sending: ${byteArrayToHexString(command)}""".trimIndent())
      val result = isoDep.transceive(command)
      val resultLength = result.size
      val statusWord = byteArrayOf(result[resultLength - 2], result[resultLength - 1])
      log("Received: ${byteArrayToHexString(result)} status: ${byteArrayToHexString(statusWord)}")
      val payload = Arrays.copyOf(result, resultLength - 2)
      if (Arrays.equals(SELECT_OK_SW, statusWord)) {
        val accountNumber = byteArrayToHexString(payload)
        log("Received: $accountNumber\n")
        if (accountNumber == "1234567890") {
          startAccountCommit(isoDep)
        } else {
          log("account number wrong\n")
        }
      }
    } catch (e: IOException) {
      loge("Error communicating with card: $e")
    }
  }

  private fun startAccountCommit(isoDep: IsoDep) {
    val command = hexStringToByteArray("0011AB")
    log("Sending: ${byteArrayToHexString(command)}")
    val result = byteArrayToHexString(isoDep.transceive(command))
    log("Received: $result")
  }

  private fun tryReaderMifareClassic(mifareClassic: MifareClassic) {
    var imgData = ""
    try {
      mifareClassic.connect()
      val type = mifareClassic.type
      val sectorCount = mifareClassic.sectorCount
      val blockCount = mifareClassic.blockCount
      val size = mifareClassic.size
      log("Tag type: $type")
      log("Sector count: $sectorCount")
      log("Block count: $blockCount")
      log("Tag size: $size")
      val key = MifareClassic.KEY_DEFAULT
      var firstEmpty = -1
      for (i in 0 until sectorCount) {
        val idxNumber = String.format("%02d", i)
        if (mifareClassic.authenticateSectorWithKeyA(i, key)) {
          val blockCount = mifareClassic.getBlockCountInSector(i)
          val blockIndex = mifareClassic.sectorToBlock(i)
          for (j in 0 until blockCount) {
            val readBlock = mifareClassic.readBlock(blockIndex + j)
            val existData = byteArrayToHexString(readBlock)
            log("Sector $idxNumber Block $j: ${byteArrayToHexString(readBlock)} length: ${readBlock.size}")

            if (i == 1 && j == 0) {
              if (existData == "00000000000000000000000000000000") {
                val data = "aoss:/spacex.jpg"
                mifareClassic.writeBlock(blockIndex + j, data.toByteArray())
              } else {
                log("hex data: $existData, toString is ${String(readBlock)}")
                val rsp = String(readBlock)
                if (rsp.startsWith("aoss:")) {
                  val url = "https://monster-image-backup.oss-cn-shanghai.aliyuncs.com/share${rsp.substring(5)}"
                  log("url: $url")
                  imgData = url
                }
              }
            }
          }

//          val block = mifareClassic.readBlock(blockIndex)
//          val data = byteArrayToHexString(block)
//          if (i == 0) {
//            firstData = data
//          }
//          if (data == "00000000000000000000000000000000" && firstData.startsWith("377082E5") && firstEmpty == -1) {
//            firstEmpty = i
//            log("first empty sector: $firstEmpty")
//          }
//          log("Sector $idxNumber: $data -> ${block.size}")
        } else {
          loge("Sector $idxNumber: authentication failed")
        }
      }
      if (firstEmpty == 1) {
        log("can write data, now write SpaceX image Url")
//        val url = "http://monster-image-backup.oss-cn-shanghai.aliyuncs.com/share/spacex.jpg"
        val url = "/spacex.jpg"
        val urlBytes = url.toByteArray()
        val header = "aoss:".toByteArray()
        log("command length: ${(header + urlBytes).size}")
//        val hexCommand = byteArrayToHexString(header + urlBytes)
//        log("img url hexCommand: $hexCommand")
//        val command = hexStringToByteArray(hexCommand)
        mifareClassic.writeBlock(mifareClassic.sectorToBlock(firstEmpty), header + urlBytes)
        log("write success")
      }
    } catch (e: IOException) {
      loge("Error communicating with card: $e")
    }
    if (imgData.isNotEmpty()) {
      EventBus.getDefault().post(ImageUrlEvent(imgData))
    }
  }

  private fun log(msg: String) {
    Log.d(TAG, msg);
    EventBus.getDefault().post(Pair(1, msg))
  }

  private fun loge(msg: String) {
    Log.e(TAG, msg);
    EventBus.getDefault().post(Pair(0, msg))
  }

  data class ImageUrlEvent(val url: String)
}