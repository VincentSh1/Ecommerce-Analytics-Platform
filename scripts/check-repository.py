#!/usr/bin/env python3
"""Lightweight source/doc checks; this is not a full secret or vulnerability scanner."""
from pathlib import Path
import re
import subprocess

root = Path(__file__).resolve().parent.parent
names = subprocess.check_output(
    ["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"], cwd=root
).decode().split("\0")
errors = []
patterns = [
    r"(?:AKIA|ASIA)[A-Z0-9]{16}",
    r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----",
    r"gh[pousr]_[A-Za-z0-9]{36,}",
    r"github_pat_[A-Za-z0-9_]{60,}",
    r"xox[baprs]-[A-Za-z0-9-]{20,}",
]
files = [root / name for name in names if name and (root / name).is_file()]
for path in files:
    relative = path.relative_to(root)
    if path.name == ".env" or (path.name.startswith(".env.") and path.name != ".env.example"):
        errors.append(f"{relative}: environment file included in repository")
    if (
        ".terraform" in relative.parts
        or path.suffix in {".tfstate", ".tfplan", ".tfvars"}
        or ".tfstate." in path.name
        or path.name.endswith(".tfvars.json")
    ):
        errors.append(f"{relative}: Terraform state, plan, or private input file included in repository")
    text = path.read_text()
    for pattern in patterns:
        if re.search(pattern, text):
            errors.append(f"{relative}: possible credential/private key")
    if re.search(r"/(?:Users|home)/[A-Za-z0-9_.-]+/", text):
        errors.append(f"{relative}: personal absolute path")
    if any(line != line.rstrip() for line in text.splitlines()):
        errors.append(f"{relative}: trailing whitespace")
    if path.suffix == ".md":
        if text.count("```") % 2:
            errors.append(f"{relative}: unmatched code fence")
        for target in re.findall(r"\]\(([^)]+)\)", text):
            if "://" not in target and not (path.parent / target.split("#")[0]).exists():
                errors.append(f"{relative}: broken relative link {target}")
if errors:
    raise SystemExit("\n".join(errors))
print(f"PASS: {len(files)} repository files; obvious key patterns, personal paths, whitespace, Markdown links/fences")
