#!/bin/bash

year=$(date +%Y)
month=$(date +%m)
day=$(date +%d)

# 指定要查找的目录
directory="pending/doc"

# 使用 find 命令递归查找所有 .doc 文件
doc_files=$(find "$directory" -type f -name "*.doc")

# 使用 for 循环遍历所有找到的文件
for file in $doc_files; do
    echo "Processing file: $file"

    groovy -cp $CLASSPATH convert_doc.groovy $file source/_posts/$year/$month/$day/$title

    rm -f file

done

