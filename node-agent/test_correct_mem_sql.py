"""Execute actual migration/query SQL against synthetic SQLite; not Room instrumentation."""
import re
import sqlite3
import unittest
from pathlib import Path

BASE = Path(__file__).resolve().parents[1] / 'android/app/src/main/java/com/jadegenesis/mobile/memory'


class MigrationAndQueries(unittest.TestCase):
    def database(self, version):
        db=sqlite3.connect(':memory:')
        db.execute('CREATE TABLE memory_events (id TEXT NOT NULL PRIMARY KEY, type TEXT NOT NULL, content TEXT NOT NULL, source TEXT NOT NULL, confidence REAL NOT NULL, originNode TEXT NOT NULL, createdAt INTEGER NOT NULL)')
        db.execute("INSERT INTO memory_events VALUES ('preserved','FACT','synthetic original','USER',1,'synthetic',1)")
        text=(BASE/'JadeDatabase.kt').read_text(encoding='utf-8')
        for migration in ('MIGRATION_1_2','MIGRATION_2_3'):
            block=text.split('val '+migration+' = ',1)[1].split('\n        }',1)[0]
            for match in re.finditer(r'connection.execSQL\(\s*((?:"[^"\n]*"\s*(?:\+\s*)?)+)\)',block):
                sql=''.join(re.findall(r'"([^"\n]*)"',match.group(1)))
                db.execute(sql)
            if migration == 'MIGRATION_1_2' and version==2: break
        return db

    def test_v1_v2_v3_keeps_existing_fact_and_indexes(self):
        db=self.database(3)
        self.assertEqual(('synthetic original',0,0,None,None),db.execute('SELECT content,lastRecalledAt,recallCount,verifiedAt,supersededBy FROM memory_events').fetchone())
        self.assertEqual(5,len(db.execute("SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='memory_events' AND name LIKE 'index_%'").fetchall()))
        self.assertEqual(8,len(db.execute('PRAGMA table_info(user_corrections)').fetchall()))

    def test_real_dao_queries_exclude_corrections_and_pending_targets(self):
        db=self.database(3)
        db.execute("INSERT INTO memory_events VALUES ('corrected','FACT','private synthetic correction','USER_CORRECTION',1,'synthetic',2,0,0,NULL,NULL)")
        db.execute("INSERT INTO user_corrections VALUES ('corrected','q','ref','wrong','private synthetic correction',2,'ACTIVE','|')")
        db.execute("INSERT INTO user_corrections VALUES ('pending','q2','ref2','wrong2','replacement',3,'PENDING','|preserved|')")
        source=(BASE/'MemoryDao.kt').read_text(encoding='utf-8')
        queries={method:sql for sql,method in re.findall(r'@Query\(\s*"""(.*?)"""\s*\)\s*suspend fun (\w+)',source,re.S)}
        args={'limit':20,'query':'synthetic','afterCreatedAt':0,'afterId':''}
        for method in ('latest','latestUserFacts','search','consolidationCandidates'):
            self.assertEqual([], db.execute(queries[method],args).fetchall(),method)
        db.execute("DELETE FROM user_corrections WHERE id='pending'")
        self.assertEqual(1,len(db.execute(queries['latest'],args).fetchall()))
        # Transaction rollback preserves both sides of a failed replacement.
        db.commit()
        try:
            with db:
                db.execute("UPDATE memory_events SET supersededBy='new' WHERE id='preserved'")
                db.execute("INSERT INTO memory_events SELECT * FROM memory_events WHERE id='preserved'")
        except sqlite3.IntegrityError: pass
        self.assertIsNone(db.execute("SELECT supersededBy FROM memory_events WHERE id='preserved'").fetchone()[0])


if __name__=='__main__': unittest.main()
