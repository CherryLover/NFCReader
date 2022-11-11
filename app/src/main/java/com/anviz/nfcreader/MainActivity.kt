package com.anviz.nfcreader

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.nfc.NfcAdapter
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.anviz.nfcreader.databinding.ActivityMainBinding
import com.bumptech.glide.Glide
import io.reactivex.Observable
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

  companion object {
    private const val TAG = "MainActivity"
    private var READER_FLAGS = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK

    @JvmStatic
    fun start(context: Context) {
      val starter = Intent(context, MainActivity::class.java)
      context.startActivity(starter)
    }
  }

  private val binding by lazy { ActivityMainBinding.inflate(layoutInflater) }
  private val reader by lazy { LoyaltyCardReader() }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(binding.root)

    binding.btnClearLog.setOnClickListener { binding.tvLog.text = "" }

    EventBus.getDefault().register(this)
  }

  override fun onPause() {
    super.onPause()
    disableReaderMode()
  }

  override fun onResume() {
    super.onResume()
    enableReaderMode()
  }

  override fun onDestroy() {
    EventBus.getDefault().unregister(this)
    super.onDestroy()
  }

  private fun enableReaderMode() {
    Log.i(TAG, "Enabling reader mode")
    val activity: Activity = this
    val nfc = NfcAdapter.getDefaultAdapter(activity)
    nfc?.enableReaderMode(activity, reader, READER_FLAGS, null)
  }

  private fun disableReaderMode() {
    Log.i(TAG, "Disabling reader mode")
    val nfc = NfcAdapter.getDefaultAdapter(this)
    nfc?.disableReaderMode(this)
  }

  @Subscribe(threadMode = ThreadMode.MAIN)
  fun receiveLog(msg: Pair<Int, String>) {
    when (msg.first) {
      0 -> {
        binding.tvLog.append("ERROR: ${msg.second} ERROR!!!\n")
      }
      1 -> {
        binding.tvLog.append("${msg.second}\n")
      }
    }
  }

  @SuppressLint("CheckResult")
  @Subscribe(threadMode = ThreadMode.MAIN, sticky = true)
  fun receiveCardId(msg: LoyaltyCardReader.ImageUrlEvent) {
    binding.ivImage.visibility = View.VISIBLE

    Glide.with(binding.ivImage)
      .asBitmap()
      .load(msg.url)
      .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
      .into(binding.ivImage)

    Observable.timer(10, TimeUnit.SECONDS)
      .subscribeOn(io.reactivex.schedulers.Schedulers.io())
      .observeOn(io.reactivex.android.schedulers.AndroidSchedulers.mainThread())
      .subscribe {
        binding.ivImage.visibility = View.GONE
      }

    Toast.makeText(this, "10s 后自动退出预览", Toast.LENGTH_SHORT).show()
  }

}