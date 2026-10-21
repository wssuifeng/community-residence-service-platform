"""Fetch the pinned N8 browser packages from vendor-hosted archives."""
from __future__ import annotations

import argparse
import concurrent.futures
import hashlib
import json
import re
import time
import urllib.error
import urllib.request
import uuid
import zipfile
from pathlib import Path


PACKAGES = {
    "chrome-154": {
        "url": "https://storage-download.googleapis.com/chrome-for-testing-public/154.0.8037.92/win64/chrome-win64.zip",
        "size": 205_614_867,
        "archive": "zip",
    },
    "chrome-155": {
        "url": "https://storage-download.googleapis.com/chrome-for-testing-public/155.0.8059.84/win64/chrome-win64.zip",
        "size": 206_114_888,
        "archive": "zip",
    },
    "firefox-157": {
        "url": "https://archive.mozilla.org/pub/firefox/releases/157.0.1/win64/en-US/Firefox%20Setup%20157.0.1.exe",
        "size": 93_445_848,
        "archive": "exe",
    },
    "firefox-158": {
        "url": "https://archive.mozilla.org/pub/firefox/releases/158.0.1/win64/en-US/Firefox%20Setup%20158.0.1.exe",
        "size": 94_890_968,
        "archive": "exe",
    },
}


def fetch_part(
    package_name: str,
    package: dict[str, object],
    output_dir: Path,
    index: int,
    chunk_size: int,
) -> tuple[int, Path, int]:
    total = int(package["size"])
    start = index * chunk_size
    end = min(total - 1, start + chunk_size - 1)
    expected = end - start + 1
    part_dir = output_dir / f"{package_name}.parts"
    part_dir.mkdir(parents=True, exist_ok=True)
    destination = part_dir / f"part-{index:04d}.bin"
    if destination.exists():
        if destination.stat().st_size == expected:
            return index, destination, expected
        raise RuntimeError(f"Existing part is incomplete and was preserved: {destination}")

    request = urllib.request.Request(
        str(package["url"]),
        headers={"Range": f"bytes={start}-{end}", "User-Agent": "N8-acceptance-evidence/1.0"},
    )
    error: Exception | None = None
    for attempt in range(4):
        temporary = destination.with_name(f"{destination.name}.{uuid.uuid4().hex}.tmp")
        try:
            with urllib.request.urlopen(request, timeout=120) as response:
                if response.status != 206:
                    raise RuntimeError(f"Expected HTTP 206, got {response.status}")
                content_range = response.headers.get("Content-Range", "")
                match = re.fullmatch(r"bytes (\d+)-(\d+)/(\d+)", content_range)
                if not match or tuple(map(int, match.groups())) != (start, end, total):
                    raise RuntimeError(f"Unexpected Content-Range: {content_range!r}")
                data = response.read()
            if len(data) != expected:
                raise RuntimeError(f"Expected {expected} bytes, received {len(data)}")
            with temporary.open("xb") as handle:
                handle.write(data)
            if destination.exists():
                raise RuntimeError(f"Refusing to replace existing part: {destination}")
            temporary.replace(destination)
            return index, destination, expected
        except (OSError, urllib.error.URLError, RuntimeError) as exc:
            error = exc
            time.sleep(min(2**attempt, 8))
        finally:
            if temporary.exists():
                temporary.unlink()
    raise RuntimeError(f"Failed package={package_name} part={index}: {error}")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest().upper()


def assemble(
    package_name: str,
    package: dict[str, object],
    output_dir: Path,
    chunk_size: int,
) -> Path:
    total = int(package["size"])
    part_dir = output_dir / f"{package_name}.parts"
    suffix = ".zip" if package["archive"] == "zip" else ".exe"
    destination = output_dir / f"{package_name}{suffix}"
    if destination.exists():
        raise RuntimeError(f"Refusing to replace existing package: {destination}")

    temporary = destination.with_name(f"{destination.name}.{uuid.uuid4().hex}.tmp")
    with temporary.open("xb") as merged:
        remaining = total
        index = 0
        while remaining:
            part = part_dir / f"part-{index:04d}.bin"
            expected = min(chunk_size, remaining)
            if not part.is_file() or part.stat().st_size != expected:
                raise RuntimeError(f"Missing or invalid part: {part}")
            with part.open("rb") as source:
                while block := source.read(1024 * 1024):
                    merged.write(block)
            remaining -= expected
            index += 1

    if temporary.stat().st_size != total:
        raise RuntimeError(f"Merged size mismatch: {temporary.stat().st_size} != {total}")
    if destination.exists():
        raise RuntimeError(f"Refusing to replace existing package: {destination}")
    temporary.replace(destination)

    if package["archive"] == "zip":
        with zipfile.ZipFile(destination) as archive:
            corrupt = archive.testzip()
        if corrupt:
            raise RuntimeError(f"ZIP CRC check failed at {corrupt}")
    return destination


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--only", nargs="+", choices=PACKAGES, required=True)
    parser.add_argument("--workers", type=int, default=8)
    parser.add_argument("--chunk-size", type=int, default=1024 * 1024)
    args = parser.parse_args()
    if args.workers < 1 or args.chunk_size < 1:
        parser.error("--workers and --chunk-size must be positive")

    args.output_dir.mkdir(parents=True, exist_ok=True)
    results = []
    for package_name in args.only:
        package = PACKAGES[package_name]
        total = int(package["size"])
        count = (total + args.chunk_size - 1) // args.chunk_size
        with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as executor:
            futures = [
                executor.submit(
                    fetch_part,
                    package_name,
                    package,
                    args.output_dir,
                    index,
                    args.chunk_size,
                )
                for index in range(count)
            ]
            completed = 0
            for future in concurrent.futures.as_completed(futures):
                future.result()
                completed += 1
                if completed % 16 == 0 or completed == count:
                    print(f"{package_name}: {completed}/{count} parts", flush=True)

        path = assemble(package_name, package, args.output_dir, args.chunk_size)
        results.append(
            {
                "package": package_name,
                "url": package["url"],
                "bytes": path.stat().st_size,
                "sha256": sha256(path),
                "path": str(path),
            }
        )

    print(json.dumps(results, indent=2), flush=True)


if __name__ == "__main__":
    main()
