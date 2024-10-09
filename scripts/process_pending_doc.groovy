#!/usr/bin/env groovy

import com.aspose.words.Document
import java.nio.file.*
import java.text.SimpleDateFormat

// 获取当前日期
def now = new Date()
def year = new SimpleDateFormat("yyyy").format(now)
def month = new SimpleDateFormat("MM").format(now)
def day = new SimpleDateFormat("dd").format(now)

// 指定要查找的目录
def directory = "pending/doc"

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

    def title = file.getFileName().toString().replace(".doc", "") // 提取文件名作为title
    
    def outputDir = new File("source/_posts/$year/$month/$day/$title")
    outputDir.mkdirs()

    def doc = new Document(file.toString())
    doc.save(new File(outputDir, "${title}.md").canonicalPath)

    // 删除文件
    Files.delete(file)
}
