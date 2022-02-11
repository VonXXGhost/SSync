package xyz.vonxxghost.script

import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.default
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import xyz.vonxxghost.script.util.FileUtils.crc
import java.io.File
import java.io.FileInputStream
import java.util.*
import kotlin.io.path.relativeTo

/**
 * Created by Vonn.Li on 2022/2/10 10:36
 */

val log = KotlinLogging.logger {}

// 文件检查模式
enum class CheckModel {
    // 名字
    NAME,

    // 名字+最后修改时间
    LAST_MOD_TIME,

    // checksum检查
    CHECKSUM
}

class Options(args: Array<String>) {

    companion object {
        // 配置文件路径
        var configFile = ""
            private set

        // 检查模式
        var checkModel = CheckModel.NAME
            private set

        // 源路径
        var srcPath = ""
            private set

        // 目标路径
        var destPath = ""
            private set

        // 仅预览检查结果
        var preview = false
            private set

        // 白名单正则
        var include = ""
            private set

        // 排除正则（优先级高于白名单）
        var exclude = ""
            private set

        // 递归子文件夹
        var recursive = false
            private set

        override fun toString(): String {
            return "configFile=$configFile; checkModel=$checkModel; srcPath=$srcPath; destPath=$destPath; " +
                    "preview=$preview; include=$include; exclude=$exclude; recursive=$recursive"
        }
    }

    init {
        val parser = ArgParser("Options")
        val configFile by parser.option(ArgType.String, shortName = "c", description = "配置文件").default(configFile)
        val checkModel by
        parser.option(ArgType.Choice<CheckModel>(), shortName = "cm", description = "检查模式")
        val srcPath by parser.option(ArgType.String, shortName = "s", description = "源路径")
        val destPath by parser.option(ArgType.String, shortName = "d", description = "目标路径")
        val preview by parser.option(ArgType.Boolean, shortName = "p", description = "仅预览检查结果")
        val include by parser.option(ArgType.String, shortName = "in", description = "白名单正则")
        val exclude by parser.option(ArgType.String, shortName = "ex", description = "排除正则")
        val recursive by parser.option(ArgType.Boolean, shortName = "r", description = "递归子文件夹")
        parser.parse(args)

        Companion.configFile = configFile
        loadConfigFromFile()

        checkModel?.let { Companion.checkModel = it }
        srcPath?.let { Companion.srcPath = it }
        destPath?.let { Companion.destPath = it }
        preview?.let { Companion.preview = it }
        include?.let { Companion.include = it }
        exclude?.let { Companion.exclude = it }
        recursive?.let { Companion.recursive = it }
        log.info { "加载配置: $Companion" }
    }

    // 从配置文件加载配置
    private fun loadConfigFromFile() {
        if (configFile.isBlank()) {
            return
        }
        val prop = Properties()
        prop.load(FileInputStream(configFile))

        val checkModelStr = prop.getProperty("checkModel") ?: prop.getProperty("cm") ?: checkModel.name
        checkModel = CheckModel.valueOf(checkModelStr.uppercase())

        srcPath = prop.getProperty("srcPath") ?: prop.getProperty("s") ?: srcPath
        destPath = prop.getProperty("destPath") ?: prop.getProperty("d") ?: destPath
        preview = (prop.getProperty("preview") ?: prop.getProperty("p"))
            ?.toBooleanStrictOrNull() ?: preview
        recursive = (prop.getProperty("recursive") ?: prop.getProperty("r"))
            ?.toBooleanStrictOrNull() ?: recursive
        include = prop.getProperty("include") ?: prop.getProperty("in") ?: include
        exclude = prop.getProperty("exclude") ?: prop.getProperty("ex") ?: exclude
    }
}

/**
 * 文件信息
 *
 * @property name 文件名
 * @property dir 父级相对路径（不包括本文件名）
 * @property absoluteDir 父级绝对路径（不包括本文件名）
 */
data class FileInfo(
    val name: String,
    val dir: String,
    val absoluteDir: String
) {
    val file: File by lazy {
        File(absoluteDir).resolve(File(name))
    }

    val lastModTime: Long
        get() = file.lastModified()

    val checksum: Long by lazy {
        file.crc()
    }
}

/**
 * 文件夹信息
 *
 * @property absolutePath 绝对路径
 * @property dirs 子文件夹
 * @property files 直接子文件
 */
data class DirectoryInfo(
    val absolutePath: String,
    val dirs: MutableList<DirectoryInfo> = mutableListOf(),
    val files: MutableList<FileInfo> = mutableListOf()
) {
    val totalFileCount: Int
        get() = calcDirsCount(this)

    private fun calcDirsCount(dir: DirectoryInfo): Int {
        var count = dir.files.size
        dirs.forEach {
            count += calcDirsCount(it)
        }
        return count
    }

    fun isEmpty(): Boolean = dirs.isEmpty() && files.isEmpty()

    /**
     * 相对于 root 的相对路径
     *
     * @param root 应为this的父路径
     * @return
     */
    fun relativePath(root: String): String {
        val thisPath = File(absolutePath).toPath()
        val rootPath = File(root).toPath()
        val relativePath = thisPath.relativeTo(rootPath)
        return relativePath.toString()
    }
}

enum class FileAction {
    ADD,
    DEL,
    OVERRIDE
}

data class SSyncDecisionResultItem(
    val action: FileAction,
    // 操作为删除时，没有src
    val srcFileInfo: FileInfo?,
    val destFileInfo: FileInfo
)

data class SSyncDecisionResult(
    val items: Map<String, List<SSyncDecisionResultItem>>
)

class SSyncDecisionTask(
    val srcDictInfo: DirectoryInfo,
    val destDictInfo: DirectoryInfo
) {

    fun makeDecision(): SSyncDecisionResult {
        runBlocking {
            var addItem: List<SSyncDecisionResultItem>
            var delItem: List<SSyncDecisionResultItem>
            var overrideItem: List<SSyncDecisionResultItem>
            launch {
                delItem = findDel()
            }
        }
    }

    private fun findDel(): List<SSyncDecisionResultItem> {
        TODO("Not yet implemented")
    }
}


/**
 * 加载目标目录下所有文件
 *
 * @param dir
 * @param recursive 递归加载
 * @param rootDir recursive为true时需要提供
 * @return
 */
fun loadAllFile(dir: String, recursive: Boolean = false, rootDir: String = ""): DirectoryInfo {
    val files = File(dir)
    val directoryInfo = DirectoryInfo(dir)
    if (!files.exists() || !files.isDirectory) {
        return directoryInfo
    }
    if (recursive && rootDir.isEmpty()) {
        throw IllegalArgumentException("rootDir can not be empty when recursive is true")
    }
    files.listFiles { file: File -> file.isFile }
        ?.forEach {
            val fileInfo = FileInfo(it.name, directoryInfo.relativePath(rootDir), dir)
            directoryInfo.files.add(fileInfo)
        }
    files.listFiles { file: File -> file.isDirectory }
        ?.forEach {
            val dictInfo = if (recursive) {
                loadAllFile(it.absolutePath, true, rootDir)
            } else {
                DirectoryInfo(it.absolutePath)
            }
            directoryInfo.dirs.add(dictInfo)
        }

    return directoryInfo
}


fun main(args: Array<String>) {
    Options(args)
    var srcDictInfo: DirectoryInfo? = null
    var destDictInfo: DirectoryInfo? = null
    runBlocking {
        launch {
            log.info { "start loading srcDictInfo" }
            srcDictInfo = loadAllFile(Options.srcPath, Options.recursive, Options.srcPath)
            log.debug { "srcDictInfo: $srcDictInfo" }
        }
        launch {
            log.info { "start loading destDictInfo" }
            destDictInfo = loadAllFile(Options.destPath, Options.recursive, Options.destPath)
            log.debug { "destDictInfo: $destDictInfo" }
        }
    }
    log.info { "All dictInfo loaded" }

}
