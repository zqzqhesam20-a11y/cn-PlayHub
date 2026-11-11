package com.kafianet.control

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import java.io.File

private object PlayHubPalette {
    val background = Color(0xFF0A0E1A)
    val surface = Color(0xFF0F1525)
    val card = Color(0xFF141B2E)
    val cardHover = Color(0xFF1A2238)
    val elevated = Color(0xFF1E2840)
    val border = Color(0xFF1E2840)
    val borderStrong = Color(0xFF2A3550)
    val text = Color(0xFFF1F5F9)
    val secondaryText = Color(0xFF94A3B8)
    val muted = Color(0xFF64748B)
    val cyan = Color(0xFF06B6D4)
    val cyanBright = Color(0xFF22D3EE)
    val blue = Color(0xFF3B82F6)
    val green = Color(0xFF10B981)
    val greenBright = Color(0xFF34D399)
    val amber = Color(0xFFF59E0B)
    val red = Color(0xFFEF4444)
}

private val playHubSections = listOf(
    "الألعاب" to "▦",
    "النقل" to "⇄",
    "التثبيت" to "↓",
    "السجل" to "◷",
    "التخزين" to "▤",
    "Shizuku" to "⬡",
    "الإعدادات" to "⚙"
)

@Composable
fun PlayHubBottomNavigation(selected: String, onSelect: (String) -> Unit) {
    Surface(
        color = PlayHubPalette.surface,
        border = BorderStroke(1.dp, PlayHubPalette.border),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(horizontal = 3.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(1.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            playHubSections.forEach { item ->
                val active = selected == item.first
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (active) PlayHubPalette.elevated else Color.Transparent)
                        .clickable { onSelect(item.first) }
                        .padding(vertical = 5.dp, horizontal = 1.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        item.second,
                        color = if (active) PlayHubPalette.cyanBright else PlayHubPalette.muted,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        item.first,
                        color = if (active) PlayHubPalette.text else PlayHubPalette.muted,
                        fontSize = 8.sp,
                        maxLines = 1,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
fun PlayHubGamesHome(
    games: List<GameItem>,
    usbSelected: Boolean,
    onTransfer: (GameItem) -> Unit,
    onInstall: (GameItem) -> Unit
) {
    Column(
        Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        Surface(
            color = PlayHubPalette.card,
            shape = RoundedCornerShape(22.dp),
            border = BorderStroke(1.dp, PlayHubPalette.border),
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                Modifier.background(
                    Brush.linearGradient(
                        listOf(Color(0x3322D3EE), Color(0x223B82F6), Color.Transparent)
                    )
                ).padding(18.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("CN-PLAYHUB", color = PlayHubPalette.cyanBright, fontSize = 10.sp, fontWeight = FontWeight.Black)
                        Text("مكتبة ألعابك في مكان واحد", color = PlayHubPalette.text, fontSize = 21.sp, fontWeight = FontWeight.Black)
                        Text(
                            "انقل ملفات الألعاب وثبّت التطبيقات وأدر مكتبتك بسهولة",
                            color = PlayHubPalette.secondaryText,
                            fontSize = 11.sp,
                            lineHeight = 17.sp
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = Color(0x3322D3EE),
                        border = BorderStroke(1.dp, Color(0x553B82F6))
                    ) {
                        Text("PH", Modifier.padding(horizontal = 14.dp, vertical = 17.dp), color = PlayHubPalette.cyanBright, fontSize = 23.sp, fontWeight = FontWeight.Black)
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            PlayHubStatCard("الألعاب", games.size.toString(), "في الكتالوج", "▦", PlayHubPalette.cyan, Modifier.weight(1f))
            PlayHubStatCard("USB", if (usbSelected) "جاهز" else "غير محدد", "مصدر خارجي", "▤", PlayHubPalette.blue, Modifier.weight(1f))
            PlayHubStatCard("النظام", "Android", "نسخ مباشر", "⬡", PlayHubPalette.green, Modifier.weight(1f))
        }

        PlayHubSectionTitle("مكتبة الألعاب", games.size.toString() + " ألعاب مدعومة")
        games.forEach { game ->
            PlayHubGameCard(
                game = game,
                primaryLabel = "النقل",
                secondaryLabel = "تثبيت APK",
                onPrimary = { onTransfer(game) },
                onSecondary = { onInstall(game) }
            )
        }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun PlayHubStatCard(label: String, value: String, detail: String, icon: String, accent: Color, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = PlayHubPalette.card,
        shape = RoundedCornerShape(15.dp),
        border = BorderStroke(1.dp, PlayHubPalette.border)
    ) {
        Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(icon, color = accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(value, color = PlayHubPalette.text, fontSize = 16.sp, fontWeight = FontWeight.Black, maxLines = 1)
            Text(label, color = PlayHubPalette.secondaryText, fontSize = 10.sp, maxLines = 1)
            Text(detail, color = PlayHubPalette.muted, fontSize = 8.sp, maxLines = 1)
        }
    }
}

@Composable
fun PlayHubSectionTitle(title: String, subtitle: String = "") {
    Row(
        Modifier.fillMaxWidth().padding(top = 3.dp, bottom = 1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = PlayHubPalette.text, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (subtitle.isNotBlank()) Text(subtitle, color = PlayHubPalette.muted, fontSize = 10.sp)
    }
}

@Composable
fun PlayHubGameCard(
    game: GameItem,
    primaryLabel: String,
    secondaryLabel: String,
    onPrimary: () -> Unit,
    onSecondary: () -> Unit
) {
    Surface(
        color = PlayHubPalette.card,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, PlayHubPalette.border),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                Surface(
                    shape = RoundedCornerShape(13.dp),
                    color = PlayHubPalette.surface,
                    modifier = Modifier.size(62.dp)
                ) {
                    androidx.compose.foundation.Image(
                        painter = painterResource(game.imageRes),
                        contentDescription = game.english,
                        modifier = Modifier.fillMaxSize().padding(4.dp)
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(game.arabic, color = PlayHubPalette.text, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(game.english, color = PlayHubPalette.secondaryText, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Surface(color = Color(0x2210B981), shape = CircleShape) {
                        Text("مدعومة للنقل", Modifier.padding(horizontal = 8.dp, vertical = 3.dp), color = PlayHubPalette.greenBright, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                Text("›", color = PlayHubPalette.cyan, fontSize = 25.sp, fontWeight = FontWeight.Light)
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("المصدر  •  " + game.sourceFolder, color = PlayHubPalette.secondaryText, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("الوجهة  •  " + game.destinationLabel, color = PlayHubPalette.muted, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onPrimary,
                    modifier = Modifier.weight(1f).height(39.dp),
                    shape = RoundedCornerShape(11.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 3.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PlayHubPalette.cyan, contentColor = Color(0xFF06131B))
                ) { Text(primaryLabel, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                OutlinedButton(
                    onClick = onSecondary,
                    modifier = Modifier.weight(1f).height(39.dp),
                    shape = RoundedCornerShape(11.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 3.dp),
                    border = BorderStroke(1.dp, PlayHubPalette.borderStrong)
                ) { Text(secondaryLabel, fontSize = 11.sp, color = PlayHubPalette.cyanBright) }
            }
        }
    }
}

@Composable
fun PlayHubSourceSelector(selected: String, onSelect: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 13.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        listOf("USB", "الوايرلس", "الهاتف").forEach { source ->
            val active = source == selected
            Surface(
                modifier = Modifier.clickable { onSelect(source) },
                shape = RoundedCornerShape(12.dp),
                color = if (active) PlayHubPalette.cyan else PlayHubPalette.card,
                border = BorderStroke(1.dp, if (active) PlayHubPalette.cyanBright else PlayHubPalette.border)
            ) {
                Text(
                    source,
                    Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    color = if (active) Color(0xFF06131B) else PlayHubPalette.secondaryText,
                    fontSize = 11.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium
                )
            }
        }
    }
}

@Composable
fun PlayHubInstallPage(
    games: List<GameItem>,
    source: String,
    onSourceChange: (String) -> Unit,
    busy: Boolean,
    status: String,
    onInstall: (GameItem) -> Unit
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 15.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp)
    ) {
        PlayHubPageHeading("تثبيت التطبيقات", "اختر مصدر ملفات APK")
        Surface(
            color = PlayHubPalette.card,
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, PlayHubPalette.border),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("مصدر ملفات التثبيت", color = PlayHubPalette.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text("يبحث التطبيق عن ملفات APK في مجلد اللعبة المحدد ثم يفتح مثبت Android الرسمي.", color = PlayHubPalette.secondaryText, fontSize = 10.sp, lineHeight = 16.sp)
                PlayHubSourceSelector(source, onSourceChange)
            }
        }
        PlayHubSectionTitle("الألعاب", games.size.toString() + " خيارات")
        games.forEach { game ->
            Surface(
                color = PlayHubPalette.card,
                shape = RoundedCornerShape(15.dp),
                border = BorderStroke(1.dp, PlayHubPalette.border),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(Modifier.padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.foundation.Image(
                        painter = painterResource(game.imageRes),
                        contentDescription = game.english,
                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(10.dp))
                    )
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(game.arabic, color = PlayHubPalette.text, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text(game.english, color = PlayHubPalette.muted, fontSize = 9.sp, maxLines = 1)
                    }
                    Button(
                        onClick = { onInstall(game) },
                        enabled = !busy,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PlayHubPalette.cyan, contentColor = Color(0xFF06131B))
                    ) { Text("تثبيت", fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }
        PlayHubStatusCard(status, busy)
    }
}

@Composable
fun PlayHubHistoryPage(busy: Boolean, status: String, progress: TransferProgress) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(15.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        PlayHubPageHeading("سجل العمليات", "حالة النقل الحالية")
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            PlayHubStatCard("الحالة", if (busy) "نشط" else "متوقف", "العملية الحالية", "◷", if (busy) PlayHubPalette.green else PlayHubPalette.blue, Modifier.weight(1f))
            PlayHubStatCard("التقدم", if (progress.totalBytes > 0L) progress.percent.toString() + "%" else "—", "من إجمالي الحجم", "%", PlayHubPalette.cyan, Modifier.weight(1f))
        }
        if (busy) PlayHubProgressCard(progress)
        Surface(
            color = PlayHubPalette.card,
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, PlayHubPalette.border),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("آخر حالة مسجلة في الجلسة", color = PlayHubPalette.text, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(status, color = PlayHubPalette.secondaryText, fontSize = 11.sp, lineHeight = 18.sp)
                Text("لا يحتفظ الإصدار الحالي بعد بسجل تاريخي دائم؛ هذه الصفحة تعرض حالة الجلسة الحية فقط.", color = PlayHubPalette.muted, fontSize = 9.sp, lineHeight = 15.sp)
            }
        }
    }
}

@Composable
fun PlayHubProgressCard(progress: TransferProgress) {
    val hasTotal = progress.totalBytes > 0L
    Surface(
        color = PlayHubPalette.card,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, PlayHubPalette.border),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("تقدم النقل الفعلي", color = PlayHubPalette.text, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (hasTotal) Text(progress.percent.toString() + "%", color = PlayHubPalette.cyanBright, fontSize = 20.sp, fontWeight = FontWeight.Black)
            }
            if (hasTotal) {
                LinearProgressIndicator(
                    progress = { progress.percent / 100f },
                    modifier = Modifier.fillMaxWidth().height(7.dp).clip(CircleShape),
                    color = PlayHubPalette.cyan,
                    trackColor = PlayHubPalette.elevated
                )
                Text(CopyEngine.formatBytes(progress.bytesDone) + " / " + CopyEngine.formatBytes(progress.totalBytes), color = PlayHubPalette.secondaryText, fontSize = 10.sp)
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(7.dp).clip(CircleShape),
                    color = PlayHubPalette.cyan,
                    trackColor = PlayHubPalette.elevated
                )
                Text("جارٍ الفحص أو الاتصال؛ لا تظهر نسبة تقديرية قبل معرفة الحجم الحقيقي.", color = PlayHubPalette.muted, fontSize = 10.sp)
            }
            Text(progress.phase, color = PlayHubPalette.cyanBright, fontSize = 10.sp)
            if (progress.currentFile.isNotBlank()) Text(progress.currentFile, color = PlayHubPalette.muted, fontSize = 9.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (progress.totalBytes > 0L) {
                Text(
                    progress.filesDone.toString() + " / " + progress.totalFiles + " ملف  •  " +
                        CopyEngine.formatBytes(progress.bytesPerSecond) + "/s",
                    color = PlayHubPalette.secondaryText,
                    fontSize = 9.sp
                )
            }
        }
    }
}

@Composable
fun PlayHubStoragePage(context: Context, usbUri: Uri?) {
    val stats = remember {
        runCatching { StatFs(Environment.getExternalStorageDirectory().absolutePath) }.getOrNull()
    }
    val total = stats?.totalBytes ?: 0L
    val free = stats?.availableBytes ?: 0L
    val used = (total - free).coerceAtLeast(0L)
    val percent = if (total > 0L) (used.toDouble() * 100.0 / total.toDouble()).toInt().coerceIn(0, 100) else 0
    val usb = remember(usbUri) {
        usbUri?.let { runCatching { DocumentFile.fromTreeUri(context, it) }.getOrNull() }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(15.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        PlayHubPageHeading("إدارة التخزين", "المساحة المتاحة على الجهاز ومصادر الملفات")
        Surface(
            color = PlayHubPalette.card,
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, PlayHubPalette.border),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("التخزين الداخلي", color = PlayHubPalette.secondaryText, fontSize = 11.sp)
                Text(CopyEngine.formatBytes(free), color = PlayHubPalette.text, fontSize = 27.sp, fontWeight = FontWeight.Black)
                Text("متاح من " + CopyEngine.formatBytes(total), color = PlayHubPalette.muted, fontSize = 10.sp)
                LinearProgressIndicator(
                    progress = { percent / 100f },
                    modifier = Modifier.fillMaxWidth().height(9.dp).clip(CircleShape),
                    color = PlayHubPalette.cyan,
                    trackColor = PlayHubPalette.elevated
                )
                Text(percent.toString() + "% مستخدم", color = PlayHubPalette.cyanBright, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            PlayHubStatCard("المستخدم", CopyEngine.formatBytes(used), "من الذاكرة الداخلية", "▤", PlayHubPalette.blue, Modifier.weight(1f))
            PlayHubStatCard("المتاح", CopyEngine.formatBytes(free), "مساحة حرة", "＋", PlayHubPalette.green, Modifier.weight(1f))
        }
        Surface(
            color = PlayHubPalette.card,
            shape = RoundedCornerShape(17.dp),
            border = BorderStroke(1.dp, PlayHubPalette.border),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("مصدر USB", color = PlayHubPalette.text, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(
                    if (usbUri == null) "لم يتم اختيار مجلد USB بعد" else "تم حفظ صلاحية الوصول إلى: " + (usb?.name ?: "مجلد USB"),
                    color = if (usbUri == null) PlayHubPalette.amber else PlayHubPalette.greenBright,
                    fontSize = 10.sp
                )
                if (usbUri != null) Text(usbUri.toString(), color = PlayHubPalette.muted, fontSize = 8.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun PlayHubShizukuPage(
    available: Boolean,
    permission: Boolean,
    ready: Boolean,
    binding: Boolean,
    failure: String?,
    onRequest: () -> Unit
) {
    val title = when {
        ready -> "الاتصال جاهز"
        binding -> "جارٍ التحقق من الاتصال"
        !available -> "Shizuku غير متصل"
        !permission -> "الإذن مطلوب"
        else -> "خدمة الملفات غير جاهزة"
    }
    val color = if (ready) PlayHubPalette.greenBright else PlayHubPalette.amber
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(15.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        PlayHubPageHeading("إدارة Shizuku", "صلاحيات النظام وخدمة الوصول إلى الملفات")
        Surface(
            color = PlayHubPalette.card,
            shape = RoundedCornerShape(22.dp),
            border = BorderStroke(1.dp, PlayHubPalette.border),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(shape = CircleShape, color = color.copy(alpha = 0.13f), modifier = Modifier.size(66.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(if (ready) "✓" else "!", color = color, fontSize = 30.sp, fontWeight = FontWeight.Black)
                    }
                }
                Text(title, color = PlayHubPalette.text, fontSize = 19.sp, fontWeight = FontWeight.Black)
                Text(
                    if (ready) "تم التأكد من استجابة Binder وخدمة الملفات فعليًا." else failure ?: "تحقق من تشغيل Shizuku ومنح التطبيق الإذن المطلوب.",
                    color = PlayHubPalette.secondaryText,
                    fontSize = 10.sp,
                    lineHeight = 16.sp
                )
                Button(
                    onClick = onRequest,
                    modifier = Modifier.fillMaxWidth().height(45.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PlayHubPalette.cyan, contentColor = Color(0xFF06131B))
                ) { Text(if (ready) "إعادة فحص الاتصال" else "طلب الإذن / إعادة الربط", fontWeight = FontWeight.Bold) }
            }
        }
        PlayHubSectionTitle("تفاصيل الحالة")
        PlayHubDetailRow("تطبيق Shizuku", if (available) "متصل" else "غير متصل", available)
        PlayHubDetailRow("إذن التطبيق", if (permission) "ممنوح" else "غير ممنوح", permission)
        PlayHubDetailRow("خدمة الملفات", if (ready) "تعمل" else if (binding) "قيد الربط" else "غير جاهزة", ready)
    }
}

@Composable
private fun PlayHubDetailRow(label: String, value: String, positive: Boolean) {
    Surface(
        color = PlayHubPalette.card,
        shape = RoundedCornerShape(13.dp),
        border = BorderStroke(1.dp, PlayHubPalette.border),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = PlayHubPalette.secondaryText, fontSize = 11.sp, modifier = Modifier.weight(1f))
            Text(value, color = if (positive) PlayHubPalette.greenBright else PlayHubPalette.amber, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun PlayHubSettingsPage(usbUri: Uri?, status: String, onChooseUsb: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(15.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        PlayHubPageHeading("الإعدادات", "مصادر الألعاب ووجهات النقل")
        PlayHubSettingsGroup("مصادر المكتبة") {
            PlayHubSettingItem("مجلد الهاتف", GameCatalog.sourceLibraryPath)
            PlayHubSettingItem("مشاركة الكمبيوتر", LanLibraryTransfer.UNC_PATH)
            PlayHubSettingItem("USB", if (usbUri == null) "غير محدد" else "تم حفظ المجلد والصلاحية")
            Button(
                onClick = onChooseUsb,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(11.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PlayHubPalette.cyan, contentColor = Color(0xFF06131B))
            ) { Text(if (usbUri == null) "اختيار مصدر USB" else "تغيير مصدر USB", fontWeight = FontWeight.Bold) }
        }
        PlayHubSettingsGroup("وجهات الألعاب") {
            GameCatalog.games.forEach { game ->
                PlayHubSettingItem(game.arabic, game.destinationLabel)
            }
        }
        PlayHubSettingsGroup("حول التطبيق") {
            PlayHubSettingItem("CN-PlayHub", "نسخة Android الأصلية • Kotlin + Jetpack Compose")
            PlayHubSettingItem("الحالة الحالية", status)
        }
    }
}

@Composable
private fun PlayHubSettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = PlayHubPalette.card,
        shape = RoundedCornerShape(17.dp),
        border = BorderStroke(1.dp, PlayHubPalette.border),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(title, color = PlayHubPalette.cyanBright, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun PlayHubSettingItem(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = PlayHubPalette.text, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        Text(value, color = PlayHubPalette.secondaryText, fontSize = 9.sp, lineHeight = 14.sp)
    }
}

@Composable
fun PlayHubPageHeading(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, color = PlayHubPalette.text, fontSize = 21.sp, fontWeight = FontWeight.Black)
        Text(subtitle, color = PlayHubPalette.muted, fontSize = 10.sp)
    }
}

@Composable
fun PlayHubStatusCard(status: String, active: Boolean) {
    Surface(
        color = PlayHubPalette.surface,
        shape = RoundedCornerShape(13.dp),
        border = BorderStroke(1.dp, PlayHubPalette.border),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).background(if (active) PlayHubPalette.cyan else PlayHubPalette.green, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(status, color = PlayHubPalette.secondaryText, fontSize = 10.sp, lineHeight = 15.sp)
        }
    }
}
