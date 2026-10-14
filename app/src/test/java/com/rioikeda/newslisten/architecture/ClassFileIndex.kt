package com.rioikeda.newslisten.architecture

import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.net.URL
import java.util.zip.ZipFile

/**
 * class の根（directory か jar）の列挙と、class file の `SourceFile` 属性の読取。
 *
 * WHY: 字句の走査では宣言の位置の判定の穴で型が黙って 0 件になる。コンパイラが書く `SourceFile` 属性で
 * class をソースのファイルへ対応付ける。kotlin-reflect は足さない（build 設定を変えない）。
 */
internal object ClassFileIndex {

    /** class file 1 つ。`sourceFile` は `SourceFile` 属性の値（無ければ null）。 */
    data class Entry(val binaryName: String, val sourceFile: String?)

    /** 根の下で、`packagePrefix` 配下の（名前に `$` を含まない）最上位の class を列挙する。 */
    fun topLevelClasses(root: URL, packagePrefix: String): List<Entry> {
        val prefixPath = packagePrefix.replace('.', '/')
        return classFiles(root, prefixPath)
            .filter { (name, _) -> !name.substringAfterLast('/').contains('$') }
            .map { (name, open) -> Entry(name.removeSuffix(".class").replace('/', '.'), open().use { readSourceFile(it) }) }
            .sortedBy { it.binaryName }
    }

    /** class を同じ class loader から読み、`SourceFile` 属性を返す。読めなければ null。 */
    fun sourceFileOf(loader: ClassLoader, binaryName: String): String? {
        val stream = loader.getResourceAsStream(binaryName.replace('.', '/') + ".class") ?: return null
        return stream.use { readSourceFile(it) }
    }

    private fun classFiles(root: URL, prefixPath: String): List<Pair<String, () -> InputStream>> {
        val file = rootFile(root)
        if (file.isDirectory) {
            val base = File(file, prefixPath)
            if (!base.isDirectory) return emptyList()
            return base.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.map { f ->
                val relative = f.relativeTo(file).invariantSeparatorsPath
                relative to { f.inputStream() as InputStream }
            }.filter { (name, _) -> insidePrefix(name, prefixPath) }.toList()
        }
        if (file.isFile && file.name.endsWith(".jar")) {
            val zip = ZipFile(file)
            return zip.use {
                zip.entries().asSequence()
                    .filter { e -> !e.isDirectory && e.name.endsWith(".class") && insidePrefix(e.name, prefixPath) }
                    .map { e ->
                        val bytes = zip.getInputStream(e).use { s -> s.readBytes() }
                        e.name to { bytes.inputStream() as InputStream }
                    }.toList()
            }
        }
        throw EnumerationFailure("class の根を列挙できない: $root（directory でも jar でもない）")
    }

    private fun insidePrefix(entryName: String, prefixPath: String): Boolean =
        entryName.startsWith("$prefixPath/")

    private fun rootFile(root: URL): File {
        val spec = root.toString()
        val unwrapped = if (spec.startsWith("jar:")) spec.removePrefix("jar:").substringBefore("!/") else spec
        return try {
            File(java.net.URI(unwrapped))
        } catch (e: Exception) {
            throw EnumerationFailure("class の根を列挙できない: $root（${e.message}）")
        }
    }

    /** class file を最小限だけ読み、クラス属性の `SourceFile` の値を返す。 */
    private fun readSourceFile(stream: InputStream): String? {
        val input = DataInputStream(stream.buffered())
        if (input.readInt() != 0xCAFEBABE.toInt()) return null
        input.readUnsignedShort()
        input.readUnsignedShort()
        val count = input.readUnsignedShort()
        val utf8 = arrayOfNulls<String>(count)
        var index = 1
        while (index < count) {
            when (val tag = input.readUnsignedByte()) {
                1 -> utf8[index] = input.readUTF()
                3, 4 -> input.skipFully(4)
                5, 6 -> { input.skipFully(8); index++ }
                7, 8, 16, 19, 20 -> input.skipFully(2)
                9, 10, 11, 12, 17, 18 -> input.skipFully(4)
                15 -> input.skipFully(3)
                else -> throw EnumerationFailure("未知の constant pool の tag: $tag")
            }
            index++
        }
        input.skipFully(6) // access flags, this class, super class
        input.skipFully(2 * input.readUnsignedShort()) // interfaces
        repeat(2) { // fields, methods
            repeat(input.readUnsignedShort()) {
                input.skipFully(6)
                skipAttributes(input)
            }
        }
        repeat(input.readUnsignedShort()) {
            val name = utf8[input.readUnsignedShort()]
            val length = input.readInt()
            if (name == "SourceFile") return utf8[input.readUnsignedShort()]
            input.skipFully(length)
        }
        return null
    }

    private fun skipAttributes(input: DataInputStream) {
        repeat(input.readUnsignedShort()) {
            input.skipFully(2)
            input.skipFully(input.readInt())
        }
    }

    private fun DataInputStream.skipFully(count: Int) {
        var left = count
        while (left > 0) {
            val skipped = skipBytes(left)
            if (skipped > 0) left -= skipped else { readByte(); left-- }
        }
    }
}
