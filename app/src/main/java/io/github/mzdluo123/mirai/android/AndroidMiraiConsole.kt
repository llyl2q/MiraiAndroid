package io.github.mzdluo123.mirai.android

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.mzdluo123.mirai.android.activity.CaptchaActivity
import io.github.mzdluo123.mirai.android.activity.MainActivity
import io.github.mzdluo123.mirai.android.script.ScriptManager
import io.github.mzdluo123.mirai.android.utils.DeviceStatus
import io.github.mzdluo123.mirai.android.utils.LoopQueue
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.mamoe.mirai.Bot
import net.mamoe.mirai.console.utils.MiraiConsoleUI
import net.mamoe.mirai.event.Listener
import net.mamoe.mirai.event.events.BotOfflineEvent
import net.mamoe.mirai.event.events.BotReloginEvent
import net.mamoe.mirai.event.subscribeAlways
import net.mamoe.mirai.event.subscribeMessages
import net.mamoe.mirai.utils.LoginSolver
import net.mamoe.mirai.utils.SimpleLogger
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class AndroidMiraiConsole(context: Context) : MiraiConsoleUI {
    val logStorage = LoopQueue<String>(300)
    val loginSolver = AndroidLoginSolver(context)
    private val scriptDir = context.getExternalFilesDir("scripts")!!
    val scriptManager: ScriptManager by lazy {
        ScriptManager(File(scriptDir, "data"), scriptDir)
    }
    private val messageSpeedStore = AtomicInteger()

    companion object {
        val TAG = AndroidLoginSolver::class.java.name
    }

    override fun createLoginSolver(): LoginSolver {
        return loginSolver
    }

    override fun prePushBot(identity: Long) {
        return
    }

    override fun pushBot(bot: Bot) {
        bot.launch {
            scriptManager.enable(bot)
        }
        bot.subscribeAlways<BotOfflineEvent>(priority = Listener.EventPriority.HIGHEST) {
            pushLog(0L, "[INFO] 发送离线通知....")
            val builder =
                NotificationCompat.Builder(
                    BotApplication.context,
                    BotApplication.OFFLINE_NOTIFICATION
                )
                    .setAutoCancel(false)
                    //禁止滑动删除
                    .setOngoing(false)
                    //右上角的时间显示
                    .setShowWhen(true)
                    .setSmallIcon(R.drawable.ic_info_black_24dp)
                    .setContentTitle("Mirai离线")
            when (this) {
                is BotOfflineEvent.Dropped -> builder.setContentText("请检查网络环境")
                is BotOfflineEvent.Force -> {
                    //设置长消息的style
                    builder.setStyle(NotificationCompat.BigTextStyle())
                    builder.setContentText(this.message)
                }
                else -> return@subscribeAlways
            }
            NotificationManagerCompat.from(BotApplication.context).apply {
                notify(BotService.OFFLINE_NOTIFICATION_ID, builder.build())
            }
        }

        bot.subscribeAlways<BotReloginEvent>(priority = Listener.EventPriority.HIGHEST) {
            pushLog(0L, "[INFO] 发送上线通知....")
            NotificationManagerCompat.from(BotApplication.context)
                .cancel(BotService.OFFLINE_NOTIFICATION_ID)
        }

        startRefreshNotificationJob(bot)
    }

    override fun pushBotAdminStatus(identity: Long, admins: List<Long>) {
        return
    }

    override fun pushLog(identity: Long, message: String) {
        logStorage.add(message)
        Log.d(TAG, message)
    }

    override fun pushLog(
        priority: SimpleLogger.LogPriority,
        identityStr: String,
        identity: Long,
        message: String
    ) {
        logStorage.add("[${priority}] $message")
        Log.d(TAG, "[${priority}] $message")
    }

    override fun pushVersion(consoleVersion: String, consoleBuild: String, coreVersion: String) {
        val applicationContext = BotApplication.context
        logStorage.add(
            """MiraiAndroid v${applicationContext.packageManager.getPackageInfo(
                applicationContext.packageName,
                0
            ).versionName}
MiraiCore v${BuildConfig.COREVERSION}
系统版本 ${Build.VERSION.RELEASE} SDK ${Build.VERSION.SDK_INT}
内存可用 ${DeviceStatus.getSystemAvaialbeMemorySize(applicationContext)}
网络 ${DeviceStatus.getCurrentNetType(applicationContext)}"""
        )
    }

    override suspend fun requestInput(hint: String): String {
        return ""
    }

    fun stop() {
        scriptManager.disable()
    }

    private fun startRefreshNotificationJob(bot: Bot) {
        messageSpeedStore.set(0)
        bot.subscribeMessages { always { messageSpeedStore.addAndGet(1) } }
        bot.launch {
            // 获取通知展示用的头像
            val avatar = downloadAvatar(bot)

            //点击进入主页
            val notifyIntent = Intent(BotApplication.context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            val notifyPendingIntent = PendingIntent.getActivity(
                BotApplication.context, 0, notifyIntent, PendingIntent.FLAG_UPDATE_CURRENT
            )

            while (isActive) {
                val notification = NotificationCompat.Builder(
                    BotApplication.context,
                    BotApplication.SERVICE_NOTIFICATION
                )
                    //设置状态栏的通知图标
                    .setSmallIcon(R.drawable.ic_extension_black_24dp)
                    //禁止用户点击删除按钮删除
                    .setAutoCancel(false)
                    //禁止滑动删除
                    .setOngoing(true)
                    //右上角的时间显示
                    .setShowWhen(true)
                    .setOnlyAlertOnce(true)
                    .setLargeIcon(avatar).setContentIntent(notifyPendingIntent)
                    .setContentTitle("MiraiAndroid正在运行")
                    .setContentText("消息速度 ${messageSpeedStore.get() * 6}/min").build()
                messageSpeedStore.set(0)
                NotificationManagerCompat.from(BotApplication.context).apply {
                    notify(BotService.NOTIFICATION_ID, notification)
                }
                delay(10 * 1000)
            }
        }
    }

    private suspend fun downloadAvatar(bot: Bot): Bitmap {
        return try {
            pushLog(0L,"[INFO] 正在加载头像....")
            val avatarData: ByteArray = HttpClient().get<ByteArray>(bot.selfQQ.avatarUrl)
            BitmapFactory.decodeByteArray(avatarData, 0, avatarData.size)
        } catch (e: Exception) {
            delay(200)
            downloadAvatar(bot)
        }
    }
}

class AndroidLoginSolver(private val context: Context) : LoginSolver() {
    lateinit var captcha: CompletableDeferred<String>
    lateinit var captchaData: ByteArray

    companion object {
        const val CAPTCHA_NOTIFICATION_ID = 2
    }

    override suspend fun onSolvePicCaptcha(bot: Bot, data: ByteArray): String? {

        captcha = CompletableDeferred()
        captchaData = data
        val notifyIntent = Intent(context, CaptchaActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val notifyPendingIntent = PendingIntent.getActivity(
            context, 0, notifyIntent, PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder =
            NotificationCompat.Builder(context, BotApplication.CAPTCHA_NOTIFICATION)
                .setContentIntent(notifyPendingIntent)
                .setAutoCancel(false)
                //禁止滑动删除
                .setOngoing(true)
                //右上角的时间显示
                .setShowWhen(true)
                .setAutoCancel(true)
                .setSmallIcon(R.drawable.ic_info_black_24dp)
                .setContentTitle("本次登录需要验证码")
                .setContentText("点击这里输入验证码")
        NotificationManagerCompat.from(context).apply {
            notify(CAPTCHA_NOTIFICATION_ID, builder.build())
        }
        return captcha.await()
    }

    override suspend fun onSolveSliderCaptcha(bot: Bot, url: String): String? {
        return ""
    }

    override suspend fun onSolveUnsafeDeviceLoginVerify(bot: Bot, url: String): String? {
        return ""
    }

}

