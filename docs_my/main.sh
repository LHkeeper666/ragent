#!/bin/bash

file="软件需求构思及描述.md"
temp_docx=".\docx\template.docx"


# for file in *.docx; do
#     # echo "$(where markitdown)"
#     markitdown "$file" > "${file%.docx}.md"
# done

# pandoc "${file}" -o "${file%.md}.pdf" --pdf-engine=xelatex --filter pandoc-crossref

pandoc "$file" --reference-doc "$temp_docx" -o ".\docx\\${file%.md}.docx"

# pandoc "$file" ·
