#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Created on Thursday Nov 28 20:20 2024

File to be executed after updating drivers or the speudo library. It add the speudo library to all files.

@author: samuel
"""

import os

# Directory and file setup
current_dir = os.getcwd()
output_dir = os.path.join(current_dir, "mergeDrivers")
os.makedirs(output_dir, exist_ok=True)

# Path to the library file
library_file = "speudoLibrary.groovy"

# Read the content of the library file
with open(library_file, 'r', encoding='utf-8') as lib_file:
    library_content = lib_file.read()

# Process all Groovy files except 'speudoLibrary.groovy'
for filename in os.listdir(current_dir):
    if filename.endswith(".groovy") and filename != library_file:
        file_path = os.path.join(current_dir, filename)

        # Read the original file content
        with open(file_path, 'r', encoding='utf-8') as original_file:
            original_content = original_file.read()

        # Combine the library content with the original content
        combined_content = original_content + \
            "\n//-- Speudo library -----------------------------------------------------------------------------------------\n" \
            + library_content

        # Save the modified content in the 'mergeDrivers' subdirectory
        output_file_path = os.path.join(output_dir, filename)
        with open(output_file_path, 'w', encoding='utf-8') as output_file:
            output_file.write(combined_content)

print(f"All Groovy files have been processed and saved in '{output_dir}'.")