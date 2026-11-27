package com.fuguang.lueying

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // 新打开（不是转屏、折叠这种重建）就从聊天开始。这些开关放在全局 object 里，
        // 进程被常驻服务（桌宠、定时任务）留着时，上次划掉前开着的「我的」等卡片会原样又开出来（第 566 版）
        if (savedInstanceState == null) {
            MineCard.open = false; MemoryCard.open = false; Projects.open = false; ChatTools.open = false; Walls.clear = false
        }
        // 状态栏、导航栏透明，背景画面铺到屏幕边缘
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        CrashLog.arm(this)
        ReplyRuntime.init(this)
        Steps.init(this)
        Notify.init(this)
        ClockTools.init(this)
        Yiyan.init(this)
        val prefs = Prefs(this)
        ModelUsage.init(prefs)
        Novel.init(this)
        Works.init(this)
        ArtworkJob.init(this)
        SentFiles.init(this)
        SentPics.init(this)
        MaterialStore.init(this)
        PageGrab.init(this)
        Illust.init(this)
        Avatars.init(this)
        Attach.init(this)
        Projects.init(this)
        ThinkTidy.dir = java.io.File(filesDir, "tidy")
        Memory.init(prefs)
        // 助理设置里照 Kelivo 多出来的那些、世界书（世界书第一次启动时把原来的条目搬过去，要在助理设置之后）
        AssistantExt.init(prefs)
        WorldBooks.init(prefs)
        Injections.init(prefs); ConvSkills.init(prefs); CtxCut.init(prefs)
        LocalTools.init(this)
        MemModel.init(prefs)
        FoldModel.init(prefs); CtxWin.init(prefs)
        ArchivedTeamWorks.load(prefs)
        Mcp.init(this, prefs)
        Speech.init(this, prefs)
        Backup.init(prefs)
        // 约定（Pacts.kt）：读进来，闹钟重新定一遍，刚过点的补上
        Pacts.init(this, prefs)
        PactRun.ready = true
        Pacts.catchUp(this)
        PactKeeper.sync(this)
        ModelFit.init(prefs)
        WebSearch.init(prefs)
        Skin.init(prefs)
        Walls.init(this, prefs)
        AppIcon.init(this, prefs)
        AdBlock.init(this, prefs)
        Downloads.init(this, prefs)
        DlSeen.init(prefs)
        YtDlp.init(this, prefs)
        if (intent?.getStringExtra("open") == "downloads") Downloads.open = true
        Notify.take(intent)
        intent?.getStringExtra("probe_magnet")?.let { MagnetProbe.run(this, it) }
        Skills.init(this, prefs)
        VideoStore.init(this, prefs)
        ClipLink.init(prefs)
        PanStore.init(prefs)
        // Telegram 模块已删：清掉它留在手机里的登录数据
        Thread { runCatching { java.io.File(filesDir, "tdlib").deleteRecursively() } }.start()
        PanAli.init(this)
        Pan123.init(this)
        Whale.init(this)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        setContent {
            var fullscreen by remember { mutableStateOf(false) }
            // 白色主题：状态栏、导航栏换深色图标
            val light = Skin.light
            androidx.compose.runtime.SideEffect {
                controller.isAppearanceLightStatusBars = light
                controller.isAppearanceLightNavigationBars = light
            }
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
                FuguangApp(
                    prefs = prefs,
                    fullscreen = fullscreen,
                    setFullscreen = { on ->
                        fullscreen = on
                        if (on) controller.hide(WindowInsetsCompat.Type.systemBars())
                        else controller.show(WindowInsetsCompat.Type.systemBars())
                    },
                )
                // 所有底部弹层画在整屏这一层：遮罩铺满，弹层贴屏幕两边和底边
                SheetLayerHost()
            }
        }
    }

    // 回到 App、窗口拿到焦点时看一眼剪贴板（安卓 10 起只有这时读得到）
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) ClipLink.check(this)
    }

    // 有的系统（如 HyperOS）焦点回来那一下还读不到剪贴板：回到前台后稍等再看一次
    override fun onResume() {
        super.onResume()
        Keep.ver++   // 从系统设置回来：「让她能按时找你」重新看一眼
        window.decorView.postDelayed({ if (hasWindowFocus()) ClipLink.check(this) }, 600)
    }

    // 点通知栏「下好了」回到 App：打开下载页；点她的通知：打开那段对话
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (intent.getStringExtra("open") == "downloads") Downloads.open = true
        Notify.take(intent)
    }

    // 看得见 App 时不发通知
    // 看得见 App 时桌宠藏起来（主页上已经有她），出去了再浮出来
    override fun onStart() { super.onStart(); Notify.foreground = true; Notify.resumeLive(); DeskPet.front(this, true) }
    override fun onStop() { Notify.foreground = false; Chats.checkpoint(); ConvStore.Saver.flush(); Reflect.flush(); DeskPet.front(this, false); super.onStop() }
}
