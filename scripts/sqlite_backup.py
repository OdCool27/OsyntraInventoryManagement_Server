
"""Consistent local SQLite backup and restore without paid tools or a running server."""
from __future__ import annotations

import argparse
from pathlib import Path
import sqlite3
from contextlib import closing


def copy_database(source: Path, destination: Path) -> None:
    source = source.resolve(strict=True)
    destination = destination.resolve()
    if source == destination:
        raise ValueError("Source and destination must differ")
    if not destination.parent.is_dir():
        raise ValueError("Destination directory must exist")
    # Exclusive creation refuses to overwrite any existing database or backup.
    with destination.open("xb"):
        pass
    try:
        with closing(sqlite3.connect(source.as_uri() + "?mode=ro", uri=True)) as original:
            with closing(sqlite3.connect(destination)) as copied:
                original.backup(copied)
                if copied.execute("PRAGMA integrity_check").fetchone() != ("ok",):
                    raise ValueError("SQLite integrity check failed")
                if copied.execute("PRAGMA foreign_key_check").fetchall():
                    raise ValueError("SQLite foreign-key check failed")
    except Exception:
        destination.unlink(missing_ok=True)
        raise


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=("backup", "restore"))
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    arguments = parser.parse_args()
    copy_database(arguments.source, arguments.destination)
    print(f"{arguments.operation.capitalize()} verified: {arguments.destination}")


if __name__ == "__main__":
    main()
