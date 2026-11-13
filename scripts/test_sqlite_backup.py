
import sqlite3
import tempfile
import unittest
from contextlib import closing
from pathlib import Path
from sqlite_backup import copy_database


class BackupTests(unittest.TestCase):
    def test_backup_reads_wal_and_restores_to_new_database(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source, backup, restored = (root / name for name in ("source.db", "backup.db", "restored.db"))
            with closing(sqlite3.connect(source)) as live:
                live.execute("PRAGMA journal_mode=WAL")
                live.execute("CREATE TABLE movements(id INTEGER PRIMARY KEY, quantity INTEGER NOT NULL)")
                live.execute("INSERT INTO movements(quantity) VALUES(7)")
                live.commit()
                copy_database(source, backup)
                live.execute("INSERT INTO movements(quantity) VALUES(3)")
                live.commit()
                copy_database(backup, restored)
                with closing(sqlite3.connect(restored)) as recovered:
                    self.assertEqual([(7,)], recovered.execute("SELECT quantity FROM movements").fetchall())
                self.assertEqual(10, live.execute("SELECT sum(quantity) FROM movements").fetchone()[0])

    def test_refuses_overwrite_and_same_database(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "source.db"
            with closing(sqlite3.connect(source)) as database:
                database.execute("CREATE TABLE records(id INTEGER)")
            with self.assertRaises(ValueError):
                copy_database(source, source)
            backup = Path(directory) / "backup.db"
            copy_database(source, backup)
            with self.assertRaises(FileExistsError):
                copy_database(source, backup)


if __name__ == "__main__":
    unittest.main()
