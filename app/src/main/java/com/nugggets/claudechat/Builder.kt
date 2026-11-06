package com.nugggets.claudechat

import java.io.File
import java.io.OutputStream
import java.util.LinkedHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Автоматический сборщик проектов, ZIP-архивов, файлов и команд Termux из ответов ИИ.
 * Позволяет ИИ создавать и обновлять целые Android/Python/Web проекты прямо в чате.
 */
object Builder {

    const val DEV_SYSTEM_ADDON = """
[СИСТЕМНАЯ ВОЗМОЖНОСТЬ ПРИЛОЖЕНИЯ CLAUDE CHAT]:
В клиентское приложение пользователя встроен автоматический генератор файлов и сборщик ZIP-архивов!
НИКОГДА не говори, что ты «не можешь прикрепить файл» или «не можешь создать бинарник/ZIP».
Ты МОЖЕШЬ отдавать готовые ZIP-архивы и файлы напрямую:
1. Когда пользователь просит изменить проект, добавить функции или прислать обновлённый ZIP-архив, выводи каждый новый или изменённый файл в таком формате:
===== app/src/main/java/.../File.kt =====
```kotlin
// полный код файла без сокращений
```
2. Приложение на телефоне пользователя автоматически перехватит все блоки `===== путь/к/файлу =====`, объединит их со всеми неизменёнными файлами и бинарниками (например `.jks`, иконками, gradle) из прикреплённого пользователем исходного ZIP-архива и покажет кнопку «📦 Скачать готовый ZIP» прямо под твоим сообщением!
3. Если в проекте нужно обновить `versionName` и `versionCode` в `app/build.gradle.kts`, обязательно выведи обновлённый `app/build.gradle.kts`, чтобы новый архив собрался с новой версией.
4. В конце ответа дай однострочную команду для Termux в блоке ```bash ... ``` (например: `cd ~/Project && git rm -rqf . && unzip -qo ~/storage/downloads/Project-v1_1.zip && git add -A && git commit -qm "1.1" && git push origin HEAD`).
"""

    private val markerHeaderRe = Regex("(?m)^={3,}\\s*([a-zA-Z0-9_./-]+\\.[a-zA-Z0-9_.-]+)\\s*={3,}\\s*$")
    private val fenceRe = Regex("```([^\n`]*)\n([\\s\\S]*?)```")
    private val pathLikeRe = Regex("^([a-zA-Z0-9_.-]+/)*[a-zA-Z0-9_.-]+\\.[a-zA-Z0-9]{1,10}$")
    private val zipNameInTextRe = Regex("([a-zA-Z0-9_.-]+-v[0-9_.-]+\\.zip|[a-zA-Z0-9_.-]+\\.zip)")

    private fun cleanPath(raw: String): String {
        var p = raw.trim().trim('`', '*', '"', '\'', ':', ' ', '=')
        while (p.startsWith("./") || p.startsWith("/")) p = p.substring(1)
        return p
    }

    private fun isValidFilePath(p: String): Boolean {
        if (p.isEmpty() || p.length > 180 || p.contains(" ") || p.contains("..")) return false
        if (p.startsWith("http:") || p.startsWith("https:")) return false
        val ext = p.substringAfterLast('.', "").lowercase()
        val nonFiles = setOf("com", "ru", "org", "net", "io", "ai", "exe")
        if (!p.contains("/") && ext in nonFiles) return false
        return pathLikeRe.matches(p)
    }

    /** Извлекает словарь [путь_к_файлу -> содержимое] из ответа модели. */
    fun extractFiles(text: String): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        if (text.isEmpty()) return out

        // 1. Поиск блоков с явным заголовком ===== path/to/file =====
        val headers = markerHeaderRe.findAll(text).toList()
        for (i in headers.indices) {
            val h = headers[i]
            val path = cleanPath(h.groupValues[1])
            if (!isValidFilePath(path)) continue
            val start = h.range.last + 1
            val end = if (i + 1 < headers.size) headers[i + 1].range.first else text.length
            val section = text.substring(start, end).trim()
            val fence = fenceRe.find(section)
            if (fence != null && fence.range.first < 80) {
                out[path] = fence.groupValues[2].trimEnd() + "\n"
            } else if (section.isNotEmpty()) {
                out[path] = section + "\n"
            }
        }

        // 2. Поиск всех ``` ... ``` блоков с указанием пути в заголовке или строке перед блоком
        for (m in fenceRe.findAll(text)) {
            val info = m.groupValues[1].trim()
            val code = m.groupValues[2]
            var detectedPath = ""

            // Проверяем info-строку: ```kotlin:app/src/Main.kt или ```app/src/Main.kt
            for (part in info.split(':', ' ', '\t')) {
                val cand = cleanPath(part)
                if (isValidFilePath(cand)) {
                    detectedPath = cand
                    break
                }
            }

            // Проверяем первую строку внутри кода: // file: path/to/file.kt или # path/to/file.py
            if (detectedPath.isEmpty()) {
                val firstLine = code.lineSequence().firstOrNull()?.trim() ?: ""
                if (firstLine.startsWith("//") || firstLine.startsWith("#") || firstLine.startsWith("<!--")) {
                    val cleaned = firstLine
                        .removePrefix("//").removePrefix("#").removePrefix("<!--").removeSuffix("-->")
                        .replace(Regex("^(?i)(file|файл|path|путь)\\s*:\\s*"), "")
                        .trim()
                    val cand = cleanPath(cleaned)
                    if (isValidFilePath(cand)) detectedPath = cand
                }
            }

            // Проверяем 1-2 непустые строки перед блоком ``` (например: ### `app/build.gradle.kts` или **MainActivity.kt**)
            if (detectedPath.isEmpty()) {
                val before = text.substring(0, m.range.first).trimEnd()
                val prevLine = before.substringAfterLast('\n', before).trim()
                // Ищем в строке токен в обратных кавычках или жирном шрифте или просто путь
                val backtick = Regex("`([^`]+)`").find(prevLine)
                if (backtick != null && isValidFilePath(cleanPath(backtick.groupValues[1]))) {
                    detectedPath = cleanPath(backtick.groupValues[1])
                } else {
                    val stripped = prevLine.replace(Regex("^[#*\\-0-9.)\\s]+"), "").trim()
                    for (tok in stripped.split(' ', '—', '-', ':')) {
                        val cand = cleanPath(tok)
                        if (isValidFilePath(cand) && (cand.contains("/") || cand in setOf(
                                "build.gradle.kts", "settings.gradle.kts", "gradle.properties",
                                "AndroidManifest.xml", "README.md", "Dockerfile", "Makefile",
                                "package.json", "requirements.txt", ".gitignore"
                            ))
                        ) {
                            detectedPath = cand
                            break
                        }
                    }
                }
            }

            if (detectedPath.isNotEmpty() && !out.containsKey(detectedPath)) {
                out[detectedPath] = code.trimEnd() + "\n"
            }
        }
        return out
    }

    /** Извлекает bash / sh / termux команды из ответа. */
    fun extractCommands(text: String): List<String> {
        val cmds = mutableListOf<String>()
        for (m in fenceRe.findAll(text)) {
            val lang = m.groupValues[1].trim().lowercase()
            val body = m.groupValues[2].trim()
            if (body.isEmpty()) continue
            val isShell = lang in setOf("bash", "sh", "shell", "zsh", "console", "termux") ||
                (lang.isEmpty() && (body.contains("git push") || body.contains("unzip ") || body.contains("cd ~/") || body.contains("gradle ") || body.contains("pkg install")))
            if (isShell) {
                cmds.add(body)
            }
        }
        return cmds
    }

    /** Определяет рекомендуемое имя выходного ZIP-архива из текста ответа или истории чата. */
    fun suggestZipName(msgText: String, chat: Chat): String {
        // Сначала ищем имя .zip в командах (например unzip -qo ~/storage/downloads/App-v1_2.zip)
        val m = zipNameInTextRe.findAll(msgText).lastOrNull()
        if (m != null) {
            return m.groupValues[1].substringAfterLast('/')
        }
        // Иначе смотрим последний прикреплённый .zip в чате и увеличиваем версию
        for (msg in chat.msgs.asReversed()) {
            for (a in msg.atts) {
                if (a.name.endsWith(".zip", true)) {
                    val base = a.name.removeSuffix(".zip").removeSuffix(".ZIP")
                    val verMatch = Regex("^(.*-v)(\\d+)_(\\d+)$").matchEntire(base)
                    if (verMatch != null) {
                        val prefix = verMatch.groupValues[1]
                        val maj = verMatch.groupValues[2]
                        val min = (verMatch.groupValues[3].toIntOrNull() ?: 0) + 1
                        return "${prefix}${maj}_${min}.zip"
                    }
                    return "${base}-updated.zip"
                }
            }
        }
        return Files.safeName(chat.title) + ".zip"
    }

    /** Находит последний исходный .orig.zip файл в истории чата (если пользователь прикреплял архив). */
    fun findBaseZipFile(attDir: File, chat: Chat): File? {
        for (msg in chat.msgs.asReversed()) {
            for (a in msg.atts) {
                if (a.name.endsWith(".zip", true)) {
                    val orig = File(attDir, a.file + ".orig.zip")
                    if (orig.exists() && orig.length() > 0) return orig
                }
            }
        }
        return null
    }

    /**
     * Собирает полноценный ZIP-архив:
     * 1) Если в чате есть базовый архив проекта (`baseZip`), копирует из него все файлы и бинарники (.jks и др.),
     *    заменяя изменённые файлы из `overrides`.
     * 2) Добавляет все новые файлы из `overrides`.
     */
    fun writeMergedZip(baseZip: File?, overrides: Map<String, String>, out: OutputStream): Int {
        val normalizedOverrides = LinkedHashMap<String, ByteArray>()
        for ((k, v) in overrides) {
            normalizedOverrides[cleanPath(k)] = v.toByteArray(Charsets.UTF_8)
        }
        val written = HashSet<String>()
        ZipOutputStream(out).use { zos ->
            if (baseZip != null && baseZip.exists()) {
                try {
                    baseZip.inputStream().use { fis ->
                        val zis = ZipInputStream(fis)
                        val buf = ByteArray(16384)
                        while (true) {
                            val entry = try { zis.nextEntry } catch (e: Exception) { break } ?: break
                            if (entry.isDirectory) continue
                            val name = cleanPath(entry.name)
                            if (name.isEmpty() || written.contains(name)) continue
                            val overrideBytes = normalizedOverrides[name]
                            zos.putNextEntry(ZipEntry(name))
                            if (overrideBytes != null) {
                                zos.write(overrideBytes)
                            } else {
                                while (true) {
                                    val n = zis.read(buf)
                                    if (n < 0) break
                                    zos.write(buf, 0, n)
                                }
                            }
                            zos.closeEntry()
                            written.add(name)
                        }
                    }
                } catch (e: Exception) {
                    Diag.logErr("writeMergedZip.base", e)
                }
            }
            for ((name, bytes) in normalizedOverrides) {
                if (written.contains(name)) continue
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
                written.add(name)
            }
        }
        return written.size
    }
}
