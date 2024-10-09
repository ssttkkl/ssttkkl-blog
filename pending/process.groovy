#!/usr/bin/env groovy

import com.aspose.words.*
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.text.SimpleDateFormat
import java.time.*
import java.time.format.DateTimeFormatter

String md_header(String title, LocalDateTime date) {
    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    return "---\ntitle: ${title}\ndate: ${date.format(formatter)}\n---\n"
}

def deleteDirectory(File dir) {
    if (dir.exists()) {
        dir.eachFile { file ->
            if (file.isDirectory()) {
                // 递归删除子目录内容
                deleteDirectory(file)
            } else {
                // 删除文件
                file.delete()
            }
        }
        // 删除目录自身
        dir.delete()
    }
}

// 指定要查找的目录
def directory = "pending"

// 使用 Files.walk 递归查找所有 .doc 文件
def docFiles = []
Files.walk(Paths.get(directory)).each { path ->
    if (Files.isRegularFile(path) && path.toString().endsWith(".doc")) {
        docFiles << path
    }
}

// 遍历所有找到的文件
docFiles.each { file ->
    println "Processing file: ${file}"

    // 获取文件修改日期
    def attrs = Files.readAttributes(Paths.get(file.toString()), BasicFileAttributes.class)
    def date = LocalDateTime.ofInstant(attrs.lastModifiedTime().toInstant(), ZoneId.systemDefault())
    def year = date.getYear()
    def month = date.getMonthValue()
    def day = date.getDayOfMonth()

    def title = file.getFileName().toString().replace(".doc", "") // 提取文件名作为title
    
    def outputDir = new File("source/_posts/$year/$month/$day")
    outputDir.mkdirs()

    def assetDir = new File(outputDir, title)
    deleteDirectory(assetDir)

    def outputFile = new File(outputDir, "${title}.md")

    def doc = new Document(file.toString())
    def saveOptions = new MarkdownSaveOptions()
    saveOptions.setImagesFolder(title)
    doc.save(outputFile.toString(), saveOptions)
    new File(title).renameTo(assetDir)

    def originalContent = outputFile.text
    def updatedContent = md_header(title, date) + originalContent
    outputFile.withWriter('UTF-8') { writer ->
        writer.write(updatedContent)
    }

    // 删除文件
    Files.delete(file)
}
