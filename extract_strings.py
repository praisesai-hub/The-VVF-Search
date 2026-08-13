import os
import re

for root, dirs, files in os.walk('app/src/main/java/com/example/ui'):
    for file in files:
        if file.endswith('.kt'):
            path = os.path.join(root, file)
            with open(path, 'r') as f:
                content = f.read()
                
            # Find Text("...") or Text(text = "...")
            matches = re.finditer(r'Text\s*\(\s*(?:text\s*=\s*)?"([^"\\]*)"', content)
            for m in matches:
                print(f"{path}: Text: {m.group(1)}")
                
            # Find contentDescription = "..."
            matches2 = re.finditer(r'contentDescription\s*=\s*"([^"\\]*)"', content)
            for m in matches2:
                print(f"{path}: contentDescription: {m.group(1)}")

