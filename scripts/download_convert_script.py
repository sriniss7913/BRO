import urllib.request
import os

url = "https://raw.githubusercontent.com/ggerganov/llama.cpp/master/convert_hf_to_gguf.py"
dest = "scripts/convert_hf_to_gguf.py"

print(f"[DOWNLOAD] Fetching convert_hf_to_gguf.py from {url}...")
urllib.request.urlretrieve(url, dest)
print(f"[SUCCESS] Downloaded to {dest}")
