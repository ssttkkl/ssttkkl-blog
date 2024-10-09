import com.aspose.words.Document

if (args.length != 2) {
    System.err.println("Usage: convert-doc <input-file> <output-destination>")
    return 1
}

def fileNameSplit = args[0].split("\\.").toList()
fileNameSplit.removeLast()
def docName = fileNameSplit.join(".")

def outputDir = new File(args[1])
outputDir.mkdirs()

def doc = new Document(args[0])
doc.save(new File(outputDir, docName + ".md").canonicalPath)
