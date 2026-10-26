package com.kafianet.control

import android.os.Environment
import java.io.File

/**
 * Single source of truth for the game cards, source folders and Android destination paths.
 * The source phone uses /storage/emulated/0/العابي; game names must match case-sensitively.
 */
data class GameItem(
    val arabic: String,
    val english: String,
    val imageRes: Int,
    val sourceFolder: String,
    /** Paths relative to the shared internal-storage root. Multiple paths are used by games with data + OBB. */
    val targetPaths: List<String>
) {
    val destinationLabel: String
        get() = targetPaths.joinToString(" + ")
}

object GameCatalog {
    val sourceLibraryPath: String
        get() = File(Environment.getExternalStorageDirectory(), "العابي").absolutePath

    val games: List<GameItem> = listOf(
        GameItem(
            arabic = "فورتنايت",
            english = "Fortnite",
            imageRes = R.drawable.fortnite,
            sourceFolder = "فورتنايت (Fortnite)",
            targetPaths = listOf("Android/data/com.epicgames.fortnite")
        ),
        GameItem(
            arabic = "جينشن إمباكت",
            english = "Genshin Impact",
            imageRes = R.drawable.genshin,
            sourceFolder = "جينشن امباكت (Genshin Impact)",
            targetPaths = listOf("Android/data/com.miHoYo.GenshinImpact")
        ),
        GameItem(
            arabic = "eFootball",
            english = "eFootball™",
            imageRes = R.drawable.efootball,
            sourceFolder = "بيس (eFootball™)",
            targetPaths = listOf(
                "Android/data/com.konami.pesam",
                "Android/obb/com.konami.pesam"
            )
        ),
        GameItem(
            arabic = "فري فاير",
            english = "Free Fire",
            imageRes = R.drawable.freefire,
            sourceFolder = "فري فاير (free fire)",
            targetPaths = listOf("Android/data/com.dts.freefireth")
        ),
        GameItem(
            arabic = "ببجي موبايل",
            english = "PUBG MOBILE",
            imageRes = R.drawable.pubg,
            sourceFolder = "ببجي (PUBG)",
            targetPaths = listOf("Android/data/com.tencent.ig/files/UE4Game/ShadowTrackerExtra/ShadowTrackerExtra")
        ),
        GameItem(
            arabic = "كول أوف ديوتي",
            english = "Call of Duty",
            imageRes = R.drawable.callofduty,
            sourceFolder = "كول اوف ديوتي (call of duty)",
            targetPaths = listOf("Android/data/com.activision.callofduty.shooter")
        ),
        GameItem(
            arabic = "جراند ثفت أوتو",
            english = "Grand Theft Auto",
            imageRes = R.drawable.gta,
            sourceFolder = "جراند ثفت اوتو (Grand Theft Auto)",
            targetPaths = listOf("Android/data/com.rockstargames.gtasa")
        )
    )

    fun bySourceFolder(name: String): GameItem? = games.firstOrNull { it.sourceFolder == name }

    fun absoluteTargetPath(relativePath: String): String {
        val normalized = relativePath.replace('\\', '/').trim('/')
        require(normalized.isNotBlank() && normalized.split('/').none { it == "." || it == ".." }) {
            "مسار وجهة اللعبة غير صالح"
        }
        return File(Environment.getExternalStorageDirectory(), normalized).absolutePath
    }
}
