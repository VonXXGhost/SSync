package xyz.vonxxghost.ssync

import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.default
import kotlinx.coroutines.*
import xyz.vonxxghost.ssync.util.FileUtils.crc
import xyz.vonxxghost.ssync.util.copyRecursively
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.Path
import kotlin.io.path.name
import kotlin.io.path.pathString
import kotlin.io.path.relativeTo
import kotlin.system.exitProcess

/**
 * Created by Vonn.Li on 2022/2/10 10:36
 */

object log {

    private val sdf = SimpleDateFormat("HH:mm:ss.SSS")

    fun info(function: () -> String) {
        info(function.invoke())
    }

    fun info(message: String) {
        println(message)
    }

    private fun nowTime(): String = sdf.format(Calendar.getInstance().time)
}

// 文件检查模式
enum class CheckModel {
    // 名字+最后修改时间+大小
    SIMPLE,

    // checksum检查
    CHECKSUM
}

class Options(args: Array<String>) {

    companion object {
        // 配置文件路径
        var configFile = ""
            private set

        // 检查模式
        var checkModel = CheckModel.SIMPLE
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

        val includeRegex by lazy { Regex(include) }

        // 排除正则（优先级高于白名单）
        var exclude = ""
            private set

        val excludeRegex by lazy { Regex(exclude) }

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
        val configFile by parser.option(ArgType.String, shortName = "c", description = "配置文件，文件编码应为UTF-8")
            .default(configFile)
        val checkModel by parser.option(ArgType.Choice<CheckModel>(), shortName = "cm", description = "检查模式")
        val srcPath by parser.option(ArgType.String, shortName = "s", description = "源路径")
        val destPath by parser.option(ArgType.String, shortName = "d", description = "目标路径")
        val preview by parser.option(ArgType.Boolean, shortName = "p", description = "是否仅预览检查结果")
        val include by parser.option(ArgType.String, shortName = "in", description = "白名单绝对路径正则")
        val exclude by parser.option(ArgType.String, shortName = "ex", description = "排除绝对路径正则")
        val recursive by parser.option(ArgType.Boolean, shortName = "r", description = "是否递归子文件夹")
        parser.parse(args)

        Companion.configFile = configFile
        loadConfigFromFile()

        checkModel?.let { Companion.checkModel = it }
        srcPath?.let { Companion.srcPath = File(it).absolutePath }
        destPath?.let { Companion.destPath = File(it).absolutePath }
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
        val fileInputStream = FileInputStream(configFile)
        prop.load(InputStreamReader(fileInputStream, Charset.forName("UTF-8")))

        val checkModelStr = prop.getProperty("checkModel") ?: prop.getProperty("cm") ?: checkModel.name
        checkModel = CheckModel.valueOf(checkModelStr.uppercase())

        srcPath = prop.getProperty("srcPath") ?: prop.getProperty("s") ?: srcPath
        srcPath = File(srcPath).absolutePath
        destPath = prop.getProperty("destPath") ?: prop.getProperty("d") ?: destPath
        destPath = File(destPath).absolutePath

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
 * @property name 文件名（或目录名）
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

    val size: Long
        get() = file.length()

    val checksum: Long by lazy {
        file.crc()
    }

    val isFile: Boolean
        get() = file.isFile

    val relativePath: String
        get() = Path(dir).resolve(name).pathString
}

/**
 * 文件夹信息
 *
 * @property root 顶层目录
 * @property absoluteDir 绝对路径
 * @property subDirs 子文件夹
 * @property files 直接子文件
 */
data class DirectoryInfo(
    val root: String,
    val absoluteDir: String,
    val subDirs: MutableList<DirectoryInfo> = mutableListOf(),
    val files: MutableList<FileInfo> = mutableListOf()
) {

    companion object {
        fun calcRelativePath(path: String, root: String): String {
            val thisPath = File(path).toPath()
            val rootPath = File(root).toPath()
            return thisPath.relativeTo(rootPath).toString()
        }
    }

    val name: String by lazy { Path(absoluteDir).name }

    val relativePath: String by lazy {
        calcRelativePath(absoluteDir, root)
    }

    fun toFileInfo(): FileInfo = FileInfo(
        name,
        calcRelativePath(Path(absoluteDir).parent.pathString, root),
        Path(absoluteDir).parent.pathString
    )
}

enum class FileAction {
    ADD,
    DEL,
    UPDATE
}

data class SSyncDecisionResultItem(
    val action: FileAction,
    // 操作为删除时，没有src
    val srcFileInfo: FileInfo?,
    val destFileInfo: FileInfo
)

data class SSyncDecisionResult(
    val addItems: MutableMap<String, List<SSyncDecisionResultItem>> = ConcurrentHashMap(),
    val delItems: MutableMap<String, List<SSyncDecisionResultItem>> = ConcurrentHashMap(),
    val updateItems: MutableMap<String, List<SSyncDecisionResultItem>> = ConcurrentHashMap(),
) {
    fun totalCount(): Int {
        var cnt = 0
        addItems.values.forEach { cnt += it.size }
        delItems.values.forEach { cnt += it.size }
        updateItems.values.forEach { cnt += it.size }
        return cnt
    }

    fun summary(): String {
        if (isEmpty()) {
            return "无任务需执行"
        }
        val summary = StringBuilder()
        with(summary) {
            fun printFunc(): (Map.Entry<String, List<SSyncDecisionResultItem>>) -> Unit = { (_, items) ->
                run {
                    items.forEach {
                        append("\t")
//                        append(if (it.destFileInfo.isDir) "D:" else "F:")
                        appendLine(it.destFileInfo.relativePath)
                    }
                }
            }

            appendLine("——分析结果——")
            appendLine("·新增:")
            addItems.forEach(printFunc())

            appendLine("·删除:")
            delItems.forEach(printFunc())

            appendLine("·更新:")
            updateItems.forEach(printFunc())
        }
        return summary.toString()
    }

    fun merge(other: SSyncDecisionResult) {
        addItems.putAll(other.addItems)
        delItems.putAll(other.delItems)
        updateItems.putAll(other.updateItems)
    }

    fun isEmpty(): Boolean = totalCount() == 0
}

class SSyncDecisionTask(
    val srcDictInfo: DirectoryInfo,
    val destDictInfo: DirectoryInfo
) {

    private val decisionResult = SSyncDecisionResult()

    private val srcFileNames by lazy {
        srcDictInfo.files.associateBy { it.name }
    }

    private val destFileNames by lazy {
        destDictInfo.files.associateBy { it.name }
    }

    private val srcDictNames by lazy {
        srcDictInfo.subDirs.associateBy { Path(it.absoluteDir).name }
    }

    private val destDictNames by lazy {
        destDictInfo.subDirs.associateBy { Path(it.absoluteDir).name }
    }

    fun makeDecision(): SSyncDecisionResult {
        runBlocking {
            launch {
                decisionResult.addItems[srcDictInfo.relativePath] = findAdd()
            }
            launch {
                decisionResult.delItems[srcDictInfo.relativePath] = findDel()
            }
            launch {
                decisionResult.updateItems[srcDictInfo.relativePath] = findUpdate()
            }
            for ((subSrc, subDest) in findBothSubDirs()) {
                launch {
                    val subResult = SSyncDecisionTask(subSrc, subDest).makeDecision()
                    decisionResult.merge(subResult)
                }
            }
        }
        return decisionResult
    }

    private fun findBothSubDirs(): List<Pair<DirectoryInfo, DirectoryInfo>> {
        return srcDictInfo.subDirs
            .filter { it.name in destDictNames.keys }
            .map { Pair(it, destDictNames[it.name]!!) }
            .toList()
    }

    /**
     * 只根据文件名/目录名判断，源目录下有，新目录下没有，就新增
     *
     * @return
     */
    private fun findAdd(): List<SSyncDecisionResultItem> {
        val addItems = mutableListOf<SSyncDecisionResultItem>()
        // 判断目录
        if (Options.recursive) {
            srcDictInfo.subDirs.forEach {
                if (it.name !in destDictNames.keys) {
                    addItems.add(
                        SSyncDecisionResultItem(
                            FileAction.ADD,
                            it.toFileInfo(),
                            geneAddDestFileInfo(it.toFileInfo())
                        )
                    )
                }
            }
        }
        // 判断文件
        srcDictInfo.files.forEach {
            if (it.name !in destFileNames.keys) {
                addItems.add(SSyncDecisionResultItem(FileAction.ADD, it, geneAddDestFileInfo(it)))
            }
        }
        return addItems
    }

    private fun geneAddDestFileInfo(src: FileInfo): FileInfo {
        // 关键在于根据相对目录生成目标的绝对目录
        val absolutePath = Path(destDictInfo.root).resolve(src.dir).toAbsolutePath().pathString
        return FileInfo(src.name, src.dir, absolutePath)
    }

    /**
     * 只根据文件名/目录名判断，源目录下没有，新目录下有，就删除
     *
     * @return
     */
    private fun findDel(): List<SSyncDecisionResultItem> {
        val delItems = mutableListOf<SSyncDecisionResultItem>()
        // 判断目录
        if (Options.recursive) {
            destDictInfo.subDirs.forEach {
                if (it.name !in srcDictNames.keys) {
                    delItems.add(SSyncDecisionResultItem(FileAction.DEL, null, it.toFileInfo()))
                }
            }
        }
        // 判断文件
        destDictInfo.files.forEach {
            if (it.name !in srcFileNames.keys) {
                delItems.add(SSyncDecisionResultItem(FileAction.DEL, null, it))
            }
        }
        return delItems
    }

    /**
     * 根据配置判断更新了的文件。
     * 因为新增、删除在其他任务里了，这里只需要管两边都有的文件即可
     *
     * @return
     */
    private suspend fun findUpdate(): List<SSyncDecisionResultItem> {
        val updateItems = mutableListOf<SSyncDecisionResultItem>()
        destDictInfo.files.forEach {
            if (it.name !in srcFileNames.keys) {
                return@forEach
            }
            val srcFileInfo = srcFileNames[it.name]
            if (checkHasUpdated(srcFileInfo!!, it)) {
                updateItems.add(SSyncDecisionResultItem(FileAction.UPDATE, srcFileInfo, it))
            }
        }
        return updateItems
    }

    /**
     * 对比两文件是否发生了变更
     *
     * @param src
     * @param dest
     * @return ture：存在变更
     */
    private suspend fun checkHasUpdated(src: FileInfo, dest: FileInfo): Boolean {
        if (!src.isFile || !dest.isFile) {
            return false
        }
        return when (Options.checkModel) {
            CheckModel.SIMPLE -> checkHasUpdatedSimple(src, dest)
            CheckModel.CHECKSUM -> checkHasUpdatedChecksum(src, dest)
        }
    }

    private fun checkHasUpdatedSimple(src: FileInfo, dest: FileInfo): Boolean {
        return src.lastModTime != dest.lastModTime || src.size != dest.size
    }

    private suspend fun checkHasUpdatedChecksum(src: FileInfo, dest: FileInfo): Boolean {
        var result: Boolean
        withContext(Dispatchers.IO) {
            result = src.checksum != dest.checksum
        }
        return result
    }
}


class SSyncDecisionExecuteTask(val decision: SSyncDecisionResult) {

    private val totalCount = decision.totalCount()
    private val processedCount = AtomicInteger()

    fun execute() {
        log.info { "同步任务开始执行" }
        executeAddTask(decision.addItems)
        executeUpdateTask(decision.updateItems)
        executeDelTask(decision.delItems)
        log.info { "同步任务执行完毕" }
    }

    private fun logProgress(item: SSyncDecisionResultItem) {
        when (item.action) {
            FileAction.ADD -> log.info {
                "(${processedCount.addAndGet(1)}/$totalCount) Copying - " +
                        "'${item.srcFileInfo?.file?.absolutePath}' to '${item.destFileInfo.file.absolutePath}'"
            }
            FileAction.DEL -> log.info {
                "(${processedCount.addAndGet(1)}/$totalCount) Deleting - " +
                        "'${item.destFileInfo.file.absolutePath}'"
            }
            FileAction.UPDATE -> log.info {
                "(${processedCount.addAndGet(1)}/$totalCount) Updating - " +
                        "'${item.srcFileInfo?.file?.absolutePath}' to '${item.destFileInfo.file.absolutePath}'"
            }
        }
    }

    private fun executeAddTask(addItems: Map<String, List<SSyncDecisionResultItem>>) {
        for ((_, items) in addItems) {
            items.forEach {
                logProgress(it)
                it.srcFileInfo!!.file.copyRecursively(it.destFileInfo.file, overwrite = false, copyAttributes = true)
            }
        }
    }

    private fun executeUpdateTask(updateItems: Map<String, List<SSyncDecisionResultItem>>) {
        for ((_, items) in updateItems) {
            items.forEach {
                logProgress(it)
                it.srcFileInfo!!.file.copyRecursively(it.destFileInfo.file, overwrite = true, copyAttributes = true)
            }
        }
    }

    private fun executeDelTask(delItems: Map<String, List<SSyncDecisionResultItem>>) {
        for ((_, items) in delItems) {
            items.forEach {
                logProgress(it)
                it.destFileInfo.file.deleteRecursively()
            }
        }
    }
}


/**
 * 加载目标目录下所有文件
 *
 * @param absolutePath
 * @param recursive 递归加载
 * @param rootDir recursive为true时需要提供
 * @return
 */
fun loadAllFile(absolutePath: String, recursive: Boolean = false, rootDir: String = ""): DirectoryInfo {
    val files = File(absolutePath)
    val directoryInfo = DirectoryInfo(rootDir, absolutePath)
    if (!files.exists() || !files.isDirectory) {
        return directoryInfo
    }
    if (recursive && rootDir.isEmpty()) {
        throw IllegalArgumentException("rootDir can not be empty when recursive is true")
    }
    files.listFiles { file: File -> file.isFile }
        ?.filter { checkIncludeAndExclude(it) }
        ?.forEach {
            val fileInfo = FileInfo(it.name, directoryInfo.relativePath, absolutePath)
            directoryInfo.files.add(fileInfo)
        }
    files.listFiles { file: File -> file.isDirectory }
        ?.filter { checkIncludeAndExclude(it) }
        ?.forEach {
            val dictInfo = if (recursive) {
                loadAllFile(it.absolutePath, true, rootDir)
            } else {
                DirectoryInfo(rootDir, it.absolutePath)
            }
            directoryInfo.subDirs.add(dictInfo)
        }

    return directoryInfo
}

fun checkIncludeAndExclude(file: File): Boolean {
    if (Options.include.isNotBlank() && !Options.includeRegex.matches(file.absolutePath)) {
        return false
    }
    if (Options.exclude.isNotBlank() && Options.excludeRegex.matches(file.absolutePath)) {
        return false
    }
    return true
}

fun readyToExit() {
    log.info { "按下回车结束程序" }
    readLine()
    exitProcess(0)
}

fun main(args: Array<String>) {
    Options(args)
    var srcDictInfo: DirectoryInfo? = null
    var destDictInfo: DirectoryInfo? = null
    runBlocking {
        launch {
            srcDictInfo = loadAllFile(Options.srcPath, Options.recursive, Options.srcPath)
        }
        launch {
            destDictInfo = loadAllFile(Options.destPath, Options.recursive, Options.destPath)
        }
    }
    log.info { "已加载目录信息" }
    val task = SSyncDecisionTask(srcDictInfo!!, destDictInfo!!)
    val decisionResult = task.makeDecision()
    log.info { decisionResult.summary() }
    if (Options.preview) {
        readyToExit()
    }
    if (decisionResult.isEmpty()) {
        readyToExit()
    }
    do {
        log.info { "是否开始执行任务[Y/N]:" }
        val ans = readLine()?.trim()?.uppercase()
        if (ans == "Y") {
            break
        } else if (ans == "N") {
            readyToExit()
        } else {
            log.info { "预料之外的输入" }
        }
    } while (true)
    SSyncDecisionExecuteTask(decisionResult).execute()
    readyToExit()
}
