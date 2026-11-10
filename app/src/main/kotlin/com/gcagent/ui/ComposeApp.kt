package com.gcagent.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color as AColor
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.Base64
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.gcagent.Db
import com.gcagent.Prefs
import com.gcagent.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import java.io.File

private val Blue = Color(0xFF4397F7)
private val Cyan = Color(0xFF00E5FF)
private val Pink = Color(0xFFF0427E)
private val BlueDeep = Color(0xFF2F7FE0)
private val Red = Color(0xFFE5484D)
private val Ink = Color(0xFF23262B)
private val Muted = Color(0xFF7B8088)
private val Line = Color(0xFFE8EAEE)
private val Soft = Color(0xFFF3F5F8)
private val Gold = Color(0xFFAA7A32)
private val Paper = Color.White

private fun root(cmd: String): String = try {
    val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
    val out = p.inputStream.bufferedReader().readText()
    p.waitFor()
    out
} catch (e: Exception) { "" }

private fun dbFile(ctx: Context): File = ctx.getDatabasePath("gcagent.db")

private fun qqConfig(): String = "/sdcard/Android/data/com.tencent.mobileqq/files/gcagent/config.json"

private fun readConfig(): Map<String, String> {
    val s = root("cat " + qqConfig() + " 2>/dev/null")
    val map = HashMap<String, String>()
    Regex("\"(host|port|account|target_oldest_seq|enabled)\"\\s*:\\s*(\"([^\"]*)\"|true|false|[-0-9]+)")
        .findAll(s).forEach { m ->
            val v = m.groupValues[3].ifEmpty { m.groupValues[2] }
            map[m.groupValues[1]] = v
        }
    return map
}

private fun writeConfig(host: String, port: String, account: String, target: String, enabled: Boolean) {
    val json = "{\"host\":\"${host.replace("\"", "")}\",\"port\":${port.toIntOrNull() ?: 18767},\"enabled\":$enabled," +
            "\"account\":\"${account.replace("\"", "")}\",\"target_oldest_seq\":${target.toIntOrNull() ?: 0}}"
    val b64 = Base64.encodeToString(json.toByteArray(), Base64.NO_WRAP)
    root("mkdir -p ${qqConfig().substringBeforeLast('/')}; echo '$b64' | base64 -d > ${qqConfig()}; chmod 666 ${qqConfig()}")
}

private fun collectContacts(ctx: Context, done: () -> Unit) {
    val cp = ctx.applicationInfo.sourceDir
    Thread {
        root("CLASSPATH=$cp app_process /system/bin com.gcagent.Exec contacts")
        done()
    }.start()
}

private data class UiData(val users: List<Db.User>, val privates: List<Db.Chat>, val groups: List<Db.Chat>) {
    val chats get() = privates + groups
}

private fun loadData(ctx: Context): UiData {
    val f = dbFile(ctx)
    val u = try { Db.listUsers(f) } catch (e: Exception) { emptyList() }
    val p = try { Db.listChats(f, 1) } catch (e: Exception) { emptyList() }
    val g = try { Db.listChats(f, 2) } catch (e: Exception) { emptyList() }
    return UiData(u, p, g)
}

private fun timeText(raw: Long): String {
    if (raw <= 0) return "暂无"
    val ms = if (raw < 100_000_000_000L) raw * 1000L else raw
    val now = System.currentTimeMillis()
    val d = (now - ms) / 1000
    if (d < 60) return "刚刚"
    if (d < 3600) return "${d / 60} 分钟前"
    if (d < 86400) return "${d / 3600} 小时前"
    if (d < 86400 * 30) return "${d / 86400} 天前"
    return java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA).format(java.util.Date(ms))
}

@Composable
private fun GcTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Blue, onPrimary = Color.White,
            background = Paper, onBackground = Ink,
            surface = Paper, onSurface = Ink,
            outline = Line, surfaceVariant = Soft,
        ), content = content
    )
}

class HomeScreenActivity : ComponentActivity() {
    private var splashReady by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        val enabled = Prefs.splashEnabled(this)
        val showSplash = savedInstanceState == null && enabled
        android.util.Log.i("GCAgentSplash", "activity show=$showSplash enabled=$enabled saved=${savedInstanceState != null}")
        val splash = installSplashScreen()
        if (!showSplash) splashReady = true
        splash.setKeepOnScreenCondition { !splashReady }
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(AColor.WHITE))
        enableEdgeToEdge()
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        setContent {
            GcTheme {
                var splashVisible by remember { mutableStateOf(showSplash) }
                var homeIn by remember { mutableStateOf(!showSplash) }
                Box(Modifier.fillMaxSize()) {
                    HomeScreen(startAnimation = homeIn, onQq = {
                        startActivity(Intent(this@HomeScreenActivity, QqScreenActivity::class.java))
                    }, onSettings = {
                        startActivity(Intent(this@HomeScreenActivity, GlassSettingsActivity::class.java))
                    })
                    if (splashVisible) {
                        SplashOverlay(screenW, screenH,
                            onReady = { splashReady = true },
                            onDone = {
                                splashReady = true
                                splashVisible = false
                                homeIn = true
                            })
                    }
                }
            }
        }
    }
}

private val DOROTHY = """
多萝西住在堪萨斯广袤的大草原中部，和农夫亨利叔叔、婶婶埃姆一同生活。他们的屋子十分简陋，建造房屋的木料都要用马车从很远的地方运来。整栋房子只有一间屋子，由四壁、地板加屋顶构成。屋里摆着一台锈迹斑斑的铁皮炉灶、一个餐具橱柜、一张桌子、三四把椅子，还有两张床。亨利叔叔和埃姆婶婶的大床摆在房间一角，多萝西的小床在另一头。房子没有阁楼，也没有地窖——只在地上挖了一处狭小地穴，叫做龙卷风地窖。一旦破坏力极强、能碾碎沿途一切建筑的龙卷风袭来，一家人就得躲进这里。地窖入口是地板中央的一块活板门，顺着梯子便能下到幽暗狭小的洞穴里。

多萝西站在门口放眼望去，目之所及只有灰蒙蒙的无垠草原。平坦旷野一直铺到天际，看不见一棵树、也看不见第二座房屋。耕地被烈日烤成一片灰土，地面布满细密裂纹。就连青草也褪去了绿意，毒辣的太阳烤焦了草叶顶端，放眼望去到处都是单调的灰色。房屋曾经刷过油漆，可日晒让漆面起泡剥落，雨水又将残漆冲刷干净，如今整栋屋子和周遭万物一样，沉闷灰暗。

埃姆婶婶刚来这里时，还是个年轻貌美的少妇。烈日与狂风也改变了她。光彩从她眼中消散，只剩下黯淡的灰；脸颊与嘴唇上的红润褪尽，同样一片灰白。她身形枯瘦憔悴，再也没有灿烂的笑脸。多萝西是孤儿，刚被送来时，她清脆的笑声总会让埃姆婶婶心头一惊，每每听见小姑娘欢快的声响，她都会捂住胸口惊呼。如今她望着多萝西，依旧满心疑惑，不明白这孩子究竟有什么值得开心的事。

亨利叔叔从来不笑。他从早到晚埋头劳作，不知欢愉为何物。从长长的胡须到粗糙的皮靴，他浑身皆是灰蒙蒙的色调，面容肃穆冷峻，平日里极少开口说话。

唯有托托能逗得多萝西开怀大笑，让她不至于像周遭一切那样死气沉沉。托托一身乌黑，并不灰扑扑的，长长的毛发顺滑柔软，一双小黑眼睛嵌在小巧滑稽的鼻子两侧，闪闪发亮。托托整日四处嬉闹，多萝西总陪着它玩耍，满心疼爱这只小狗。
"""

@Composable
private fun HomeScreen(startAnimation: Boolean, onQq: () -> Unit, onSettings: () -> Unit) {
    val top = remember { Animatable(0f) }
    var cardsIn by remember { mutableStateOf(false) }
    LaunchedEffect(startAnimation) {
        if (startAnimation) {
            top.animateTo(1f, tween(320, easing = FastOutSlowInEasing))
            cardsIn = true
        }
    }
    Column(Modifier.fillMaxSize().background(Color.White).statusBarsPadding().padding(horizontal = 22.dp)) {
        Spacer(Modifier.height(18.dp))
        Row(
            Modifier.fillMaxWidth().graphicsLayer {
                alpha = top.value
                translationY = (1f - top.value) * (-26).dp.toPx()
            },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = painterResource(R.drawable.logo_home),
                contentDescription = "GCAgent",
                modifier = Modifier.width(210.dp).height(78.dp),
                contentScale = ContentScale.Fit
            )
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(38.dp).clip(CircleShape).clickable { onSettings() }, contentAlignment = Alignment.Center) {
                Image(painterResource(R.drawable.ic_gear), contentDescription = "界面设置", modifier = Modifier.size(24.dp))
            }
        }
        Spacer(Modifier.height(26.dp))
        HomeGrid(onQq, cardsIn, Modifier.weight(1f))
    }
}

@Composable
private fun HomeGrid(onQq: () -> Unit, startCards: Boolean, modifier: Modifier = Modifier) {
    val cells = 6
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.SpaceEvenly) {
        for (r in 0 until 3) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                for (c in 0 until 2) {
                    val index = r * 2 + c
                    val enter = remember(index) { Animatable(0f) }
                    LaunchedEffect(startCards, index) {
                        if (startCards) {
                            delay(index * 70L)
                            enter.animateTo(1f, tween(340, easing = FastOutSlowInEasing))
                        }
                    }
                    Box(
                        Modifier.weight(1f).aspectRatio(1f)
                            .graphicsLayer {
                                alpha = enter.value
                                translationX = (1f - enter.value) * (if (index % 2 == 0) -80f else 80f)
                            }
                            .clip(RoundedCornerShape(30.dp))
                            .background(if (index == 0) Blue else Color(0xFFF7F8FA))
                            .border(1.dp, if (index == 0) Color.Transparent else Line, RoundedCornerShape(30.dp))
                            .clickable(enabled = index == 0) { if (index == 0) onQq() },
                        contentAlignment = Alignment.Center
                    ) {
                        if (index == 0) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Image(
                                    painterResource(R.drawable.qq_symbol),
                                    contentDescription = "QQ备份",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.size(74.dp)
                                )
                                Spacer(Modifier.height(8.dp))
                                Text("QQ备份", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                            }
                        } else {
                            Text("待添加", color = Color(0xFFB7BCC5), fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

class QqScreenActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { GcTheme { QqScreen(onBack = { finish() }) } }
    }
}

@Composable
private fun QqScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val activity = ctx as Activity
    var data by remember { mutableStateOf(UiData(emptyList(), emptyList(), emptyList())) }
    val pagerState = rememberPagerState(pageCount = { 4 })
    var selected by rememberSaveable { mutableIntStateOf(0) }
    var navJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var collecting by remember { mutableStateOf(false) }
    var selectedUser by remember { mutableStateOf<Db.User?>(null) }
    var selectedChat by remember { mutableStateOf<Db.Chat?>(null) }
    var opacity by remember { mutableIntStateOf(Prefs.glassOpacity(ctx)) }
    var contentReady by remember { mutableStateOf(false) }

    fun reload() {
        loading = true
        Thread {
            val d = loadData(ctx)
            activity.runOnUiThread { data = d; loading = false; contentReady = true }
        }.start()
    }
    LaunchedEffect(Unit) { reload() }

    fun goTo(index: Int) {
        if (index !in 0..3 || index == selected) return
        navJob?.cancel()
        selected = index
        navJob = scope.launch {
            val myJob = coroutineContext.job
            try {
                pagerState.animateScrollToPage(
                    index,
                    animationSpec = spring(dampingRatio = 0.84f, stiffness = 380f)
                )
            } finally {
                if (navJob === myJob) {
                    navJob = null
                    selected = pagerState.currentPage
                }
            }
        }
    }

    LaunchedEffect(pagerState.settledPage) {
        if (navJob == null) selected = pagerState.settledPage
    }

    val backdrop = rememberLayerBackdrop {
        drawRect(Color.White)
        drawContent()
    }
    Box(Modifier.fillMaxSize().background(Color.White)) {
        Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("‹", fontSize = 34.sp, color = Ink, modifier = Modifier.clickable { onBack() }.padding(end = 12.dp))
                    Text("QQ备份", color = Blue, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    if (loading || collecting) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Blue)
                            Text(if (collecting) "同步中" else "读取中", color = Blue, fontSize = 11.sp)
                        }
                    }
                }
                Box(Modifier.weight(1f)) {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                        beyondViewportPageCount = if (contentReady) 3 else 1,
                        overscrollEffect = null,
                    ) { page ->
                        when (page) {
                            0 -> OverviewPage(data, collecting, onSeeAll = { goTo(2) }, onCollect = {
                                if (!collecting) {
                                    collecting = true
                                    collectContacts(ctx) { activity.runOnUiThread { collecting = false; reload() } }
                                }
                            }, onRefresh = { reload() })
                            1 -> UsersPage(data.users) { selectedUser = it }
                            2 -> ChatsPage(data) { selectedChat = it }
                            else -> SettingsPage(onReload = { reload() })
                        }
                    }
                }
            }
        }
        GcLiquidBar(
            labels = listOf("概览", "好友", "群聊", "设置"),
            selected = selected,
            opacity = opacity,
            backdrop = backdrop,
            onSelect = { goTo(it) },
            modifier = Modifier.align(Alignment.BottomCenter).padding(start = 24.dp, end = 24.dp, bottom = 34.dp)
        )
    }

    selectedUser?.let { u ->
        DetailDialog(title = "好友资料", lines = listOf(
            "显示名称" to (u.displayName() ?: "未知"),
            "QQ 号" to (u.uin ?: "暂无"),
            "昵称" to (u.nick ?: "暂无"),
            "备注" to (u.remark ?: "暂无"),
            "身份" to "好友",
            "资料更新" to timeText(u.profileDatetimeMs),
            "最近入库" to timeText(u.lastSeenAt),
        ), onDismiss = { selectedUser = null })
    }
    selectedChat?.let { c ->
        DetailDialog(title = "会话资料", lines = listOf(
            "会话名称" to c.displayName(),
            "类型" to (if (c.group()) "群聊" else "私聊"),
            (if (c.group()) "群号" else "QQ 号") to (c.peerUid ?: "暂无"),
            "最后消息" to timeText(c.lastMsgTime),
            "首次入库" to timeText(c.firstSeenAt),
            "最近收录" to timeText(c.lastSeenAt),
        ), onDismiss = { selectedChat = null })
    }
}

@Composable
private fun SectionTitle(text: String, sub: String? = null) {
    Column(Modifier.padding(top = 8.dp, bottom = 10.dp)) {
        Text(text, color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        if (sub != null) Text(sub, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
private fun OverviewPage(data: UiData, collecting: Boolean, onSeeAll: () -> Unit, onCollect: () -> Unit, onRefresh: () -> Unit) {
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 18.dp)) {
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
                .background(Brush.linearGradient(listOf(Blue, BlueDeep))).padding(22.dp)
        ) {
            Column {
                Text("备份资料库", color = Color.White.copy(alpha = 0.86f), fontSize = 13.sp)
                Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 6.dp)) {
                    Text("${data.users.size}", color = Color.White, fontSize = 48.sp, fontWeight = FontWeight.Bold)
                    Text(" 位好友", color = Color.White.copy(alpha = 0.9f), fontSize = 16.sp, modifier = Modifier.padding(bottom = 8.dp))
                }
                Text("资料会话、好友与群组，都安静地留在这台手机里。", color = Color.White.copy(alpha = 0.9f), fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("群聊 ${data.groups.size}")
                    Pill("私聊 ${data.privates.size}")
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            StatCard("好友", data.users.size, "已收录", Modifier.weight(1f))
            StatCard("群聊", data.groups.size, "已建档", Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            StatCard("私聊会话", data.privates.size, "可备份", Modifier.weight(1f))
            StatCard("全部会话", data.chats.size, "资料目录", Modifier.weight(1f))
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onCollect, enabled = !collecting, modifier = Modifier.weight(1f).height(50.dp),
                shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = Blue)) {
                Text(if (collecting) "采集中…" else "采集联系人", fontSize = 15.sp)
            }
            OutlinedButton(onClick = onRefresh, modifier = Modifier.weight(1f).height(50.dp),
                shape = RoundedCornerShape(16.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Line)) {
                Text("刷新资料", color = Ink, fontSize = 15.sp)
            }
        }
        Text("采集与补齐在虚拟屏幕中完成，不会打断正在使用的 QQ。", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp))
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("最近入库", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text("查看全部 ›", color = Blue, fontSize = 12.sp, modifier = Modifier.clickable { onSeeAll() })
        }
        val recent = (data.groups + data.privates).sortedByDescending { it.lastSeenAt }.take(4)
        recent.forEach { c -> ChatRow(c) { } }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun Pill(text: String) {
    Box(Modifier.clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.18f)).padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text(text, color = Color.White, fontSize = 12.sp)
    }
}

@Composable
private fun StatCard(label: String, value: Int, sub: String, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(22.dp)).background(Color.White).border(1.dp, Line, RoundedCornerShape(22.dp)).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(26.dp).clip(RoundedCornerShape(8.dp)).background(Soft), contentAlignment = Alignment.Center) {
                Text(label.take(1), color = Blue, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Text(label, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
        }
        Text("$value", color = Ink, fontSize = 32.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
        Text(sub, color = Muted, fontSize = 11.sp)
    }
}

@Composable
private fun UsersPage(users: List<Db.User>, onClick: (Db.User) -> Unit) {
    var q by rememberSaveable { mutableStateOf("") }
    val list = users.filter { u ->
        q.isBlank() || (u.displayName() ?: "").contains(q, true) || (u.uin ?: "").contains(q)
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        OutlinedTextField(q, { q = it }, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("搜索昵称、备注或 QQ 号", color = Muted, fontSize = 13.sp) },
            singleLine = true, shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Line, focusedBorderColor = Blue))
        Text("共 ${list.size} 位好友", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 10.dp))
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp)) {
            items(list, key = { it.id }) { u ->
                Row(Modifier.fillMaxWidth().clickable { onClick(u) }.padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(u.displayName() ?: "未知好友", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("QQ ${u.uin ?: "暂无"}${if (!u.nick.isNullOrBlank()) " · ${u.nick}" else ""}", color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                    }
                    Tag("好友", Blue)
                }
                HorizontalDivider(color = Line.copy(alpha = 0.7f))
            }
        }
    }
}

@Composable
private fun ChatsPage(data: UiData, onClick: (Db.Chat) -> Unit) {
    var group by rememberSaveable { mutableStateOf(false) }
    var q by rememberSaveable { mutableStateOf("") }
    val all = if (group) data.groups else data.privates
    val list = all.filter { c -> q.isBlank() || (c.displayName() ?: "").contains(q, true) || (c.peerUid ?: "").contains(q) }
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Soft).padding(4.dp)) {
            Segment("私聊 · ${data.privates.size}", !group, Modifier.weight(1f)) { group = false }
            Segment("群聊 · ${data.groups.size}", group, Modifier.weight(1f)) { group = true }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(q, { q = it }, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("搜索会话名称或号码", color = Muted, fontSize = 13.sp) },
            singleLine = true, shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Line, focusedBorderColor = Blue))
        Text("共 ${list.size} 个会话", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 10.dp))
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp)) {
            items(list, key = { it.id }) { c ->
                ChatRow(c) { onClick(c) }
            }
        }
    }
}

@Composable
private fun Segment(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.height(34.dp).clip(RoundedCornerShape(11.dp))
        .background(if (selected) Color.White else Color.Transparent)
        .clickable { onClick() }, contentAlignment = Alignment.Center) {
        Text(text, color = if (selected) Blue else Muted, fontSize = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
    }
}

@Composable
private fun ChatRow(c: Db.Chat, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(c.displayName(), color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val id = if (c.group()) "群号 ${c.peerUid ?: "暂无"}" else "QQ ${c.peerUid ?: "暂无"}"
            Text("$id · ${timeText(c.lastMsgTime)}", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Tag(if (c.group()) "群聊" else "私聊", if (c.group()) Gold else Blue)
    }
    HorizontalDivider(color = Line.copy(alpha = 0.7f))
}

@Composable
private fun Tag(text: String, color: Color) {
    Box(Modifier.clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = 0.08f)).padding(horizontal = 8.dp, vertical = 4.dp)) {
        Text(text, color = color, fontSize = 10.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SettingsPage(onReload: () -> Unit) {
    val ctx = LocalContext.current
    var cfg by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var host by rememberSaveable { mutableStateOf("127.0.0.1") }
    var port by rememberSaveable { mutableStateOf("18767") }
    var account by rememberSaveable { mutableStateOf("default") }
    var target by rememberSaveable { mutableStateOf("449") }
    var enabled by rememberSaveable { mutableStateOf(true) }
    var status by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val c = readConfig(); cfg = c
        host = c["host"] ?: host; port = c["port"] ?: port
        account = c["account"] ?: account; target = c["target_oldest_seq"] ?: target
        enabled = c["enabled"] != "false"
        status = "资料库：${loadData(ctx).users.size} 位好友 · ${loadData(ctx).chats.size} 个会话"
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        SectionTitle("连接电脑", "采集结果会写入本地资料库，原始聊天记录只留在本机。")
        Field("电脑地址", host, { host = it })
        Field("端口", port, { port = it }, KeyboardType.Number)
        Field("资料库账号", account, { account = it })
        Field("最早补齐位置（0 表示不限制）", target, { target = it }, KeyboardType.Number)
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(enabled, { enabled = it }, colors = CheckboxDefaults.colors(checkedColor = Blue))
            Text("启用采集与发送", color = Ink, fontSize = 14.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = {
                writeConfig(host, port, account, target, enabled)
                Toast.makeText(ctx, "配置已保存", Toast.LENGTH_SHORT).show()
            }, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(15.dp), colors = ButtonDefaults.buttonColors(containerColor = Blue)) {
                Text("保存配置", fontSize = 14.sp)
            }
            OutlinedButton(onClick = { onReload() }, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(15.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Line)) {
                Text("刷新状态", color = Ink, fontSize = 14.sp)
            }
        }
        Spacer(Modifier.height(22.dp))
        SectionTitle("本地资料库", "仅保存在本机 · 不上传原始聊天记录")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = { onReload() }, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(15.dp), colors = ButtonDefaults.buttonColors(containerColor = Blue)) {
                Text("重新读取", fontSize = 14.sp)
            }
            OutlinedButton(onClick = {
                collectContacts(ctx) { onReload() }
            }, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(15.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Line)) {
                Text("采集联系人", color = Ink, fontSize = 14.sp)
            }
        }
        Spacer(Modifier.height(22.dp))
        SectionTitle("运行状态")
        Text(status, color = Muted, fontSize = 13.sp, lineHeight = 20.sp)
        Spacer(Modifier.height(110.dp))
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, keyboard: KeyboardType = KeyboardType.Text) {
    Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Text(label, color = Muted, fontSize = 12.sp)
        OutlinedTextField(value, onChange, modifier = Modifier.fillMaxWidth().padding(top = 4.dp), singleLine = true,
            shape = RoundedCornerShape(14.dp), keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Line, focusedBorderColor = Blue))
    }
}

@Composable
private fun DetailDialog(title: String, lines: List<Pair<String, String>>, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Color(0xFFFFFCF7)).padding(22.dp)) {
            Text(title, color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("只读资料 · 仅保存在本机", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
            lines.forEach { (k, v) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
                    Text(k, color = Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text(v, color = Ink, fontSize = 13.sp, modifier = Modifier.weight(1.4f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                }
                HorizontalDivider(color = Line.copy(alpha = 0.6f))
            }
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(46.dp),
                shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = Blue)) {
                Text("关闭")
            }
        }
    }
}

class GlassSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { GcTheme { GlassSettings(onBack = { finish() }) } }
    }
}

@Composable
private fun GlassSettings(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var value by remember { mutableIntStateOf(Prefs.glassOpacity(ctx)) }
    var splashOn by remember { mutableStateOf(Prefs.splashEnabled(ctx)) }
    val previewBackdrop = rememberLayerBackdrop {
        drawRect(Color.White)
        drawContent()
    }
    Column(Modifier.fillMaxSize().background(Color.White).statusBarsPadding().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹", fontSize = 34.sp, color = Ink, modifier = Modifier.clickable { onBack() }.padding(end = 12.dp))
            Text("界面设置", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        Text("透明度", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        Text("${value}%", color = Blue, fontSize = 42.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
        Slider(value = value.toFloat(), onValueChange = { v ->
            value = v.toInt(); Prefs.setGlassOpacity(ctx, value)
        }, valueRange = 0f..100f, colors = SliderDefaults.colors(thumbColor = Blue, activeTrackColor = Blue, inactiveTrackColor = Line))
        Text("100% 几乎完全透视；0% 保留完整玻璃质感。设置会立即生效。", color = Muted, fontSize = 12.sp)
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("显示开屏动画", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("下次启动生效", color = Muted, fontSize = 11.sp)
            }
            Switch(checked = splashOn, onCheckedChange = {
                splashOn = it
                Prefs.setSplashEnabled(ctx, it)
            }, colors = SwitchDefaults.colors(checkedTrackColor = Blue, uncheckedTrackColor = Line))
        }
        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(28.dp))
            .background(Color.White).border(1.dp, Line, RoundedCornerShape(28.dp))) {
            Box(Modifier.fillMaxSize().layerBackdrop(previewBackdrop)) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
                    Text("多萝西与托托", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text(DOROTHY.trim(), color = Color(0xFF4B5058), fontSize = 14.sp, lineHeight = 24.sp, modifier = Modifier.padding(top = 10.dp))
                    Spacer(Modifier.height(110.dp))
                }
            }
            GcLiquidBar(listOf("概览", "好友", "群聊", "设置"), 0, value, previewBackdrop, {},
                Modifier.align(Alignment.BottomCenter).padding(start = 18.dp, end = 18.dp, bottom = 20.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text("上下滚动预览文字，观察菜单栏下方的模糊与透射。", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 10.dp))
    }
}
