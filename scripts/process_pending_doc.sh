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
    hexo_output=$(hexo new "$title" -p /$year/$month/$day/$title/$title.md)

    # 输出示例：INFO  Created: ~/my-hexo-site/source/_posts/my-new-post.md
    # 从命令输出中解析创建的文件路径
    # 这里假设输出中的路径位于最后一段，并且输出包含 "Created: " 前缀
    created_file=$(echo "$hexo_output" | grep -oP 'Created: \K(.*)')

    groovy -cp $CLASSPATH convert_doc.groovy $file source/_posts/$year/$month/$day/$title

    rm -f file

done

