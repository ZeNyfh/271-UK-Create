from __future__ import annotations

from functools import lru_cache
from pathlib import Path
import argparse
import json
import shlex
import sys
from typing import Any


def _strip_comment(line: str) -> str:
    in_single = False
    in_double = False
    escaped = False
    out: list[str] = []
    for char in line:
        if escaped:
            out.append(char)
            escaped = False
            continue
        if char == "\\":
            out.append(char)
            escaped = True
            continue
        if char == "'" and not in_double:
            in_single = not in_single
            out.append(char)
            continue
        if char == '"' and not in_single:
            in_double = not in_double
            out.append(char)
            continue
        if char == "#" and not in_single and not in_double:
            break
        out.append(char)
    return "".join(out).rstrip()


def _parse_scalar(raw: str) -> Any:
    value = raw.strip()
    if value == "":
        return ""
    if value[:1] in {'"', "'"} and value[-1:] == value[:1] and len(value) >= 2:
        return value[1:-1]
    lowered = value.lower()
    if lowered in {"null", "~"}:
        return None
    if lowered == "true":
        return True
    if lowered == "false":
        return False
    if value.startswith("{") or value.startswith("["):
        return json.loads(value)
    try:
        if any(char in value for char in (".", "e", "E")):
            return float(value)
        return int(value)
    except ValueError:
        return value


def parse_simple_yaml(text: str) -> dict[str, Any]:
    root: dict[str, Any] = {}
    stack: list[tuple[int, dict[str, Any]]] = [(-1, root)]
    for lineno, raw_line in enumerate(text.splitlines(), start=1):
        line = _strip_comment(raw_line)
        if not line.strip():
            continue
        indent = len(line) - len(line.lstrip(" "))
        if indent % 2 != 0:
            raise ValueError(f"Unsupported indentation in YAML at line {lineno}: {raw_line!r}")
        content = line[indent:]
        key, sep, remainder = content.partition(":")
        if not sep:
            raise ValueError(f"Expected key: value mapping at line {lineno}: {raw_line!r}")
        key = key.strip()
        if not key:
            raise ValueError(f"Missing key at line {lineno}: {raw_line!r}")
        while indent <= stack[-1][0]:
            stack.pop()
        current = stack[-1][1]
        value = remainder.strip()
        if value == "":
            child: dict[str, Any] = {}
            current[key] = child
            stack.append((indent, child))
        else:
            current[key] = _parse_scalar(value)
    return root


@lru_cache(maxsize=16)
def load_config(path: str) -> dict[str, Any]:
    config_path = Path(path)
    if not config_path.is_file():
        raise FileNotFoundError(f"Missing config file: {config_path}")
    return parse_simple_yaml(config_path.read_text(encoding="utf-8"))


def get_config_value(config_path: Path, dotted_path: str, default: Any = None) -> Any:
    current: Any = load_config(str(config_path))
    for part in dotted_path.split("."):
        if not isinstance(current, dict) or part not in current:
            return default
        current = current[part]
    return current


def _shell_scalar(value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, (int, float)):
        return str(value)
    return str(value)


def shell_assignments(config_path: Path, section: str) -> str:
    mapping = get_config_value(config_path, section)
    if mapping is None:
        raise KeyError(f"Missing config section: {section}")
    if not isinstance(mapping, dict):
        raise TypeError(f"Config section {section!r} must be a mapping")
    lines: list[str] = []
    for key, value in mapping.items():
        if isinstance(value, dict):
            raise TypeError(f"Nested mapping {section}.{key} is not supported for shell export")
        lines.append(f"{key}={shlex.quote(_shell_scalar(value))}")
    return "\n".join(lines)


def _main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="Read simple YAML config values.")
    parser.add_argument("--config", required=True, type=Path)
    parser.add_argument("--section")
    parser.add_argument("--key")
    parser.add_argument("--default")
    parser.add_argument("--format", choices=("shell", "value"), default="value")
    args = parser.parse_args(argv)

    if args.section:
        if args.format != "shell":
            raise SystemExit("--section currently only supports --format shell")
        print(shell_assignments(args.config, args.section))
        return 0
    if not args.key:
        raise SystemExit("either --section or --key is required")
    value = get_config_value(args.config, args.key, args.default)
    if value is None:
        return 1
    print(_shell_scalar(value))
    return 0


if __name__ == "__main__":
    raise SystemExit(_main(sys.argv[1:]))
