"""Read-only production schema metadata export. No application rows or credentials."""
import importlib.util
import json
import subprocess
import xml.etree.ElementTree as ET

spec=importlib.util.spec_from_file_location('release_stage','/tmp/release-stage.py');release=importlib.util.module_from_spec(spec);spec.loader.exec_module(release)
with release.MySQL(release.live_env()) as db:
    def query(sql):
        result=subprocess.check_output(['mysql','--defaults-extra-file='+str(db.config),'--default-character-set=utf8mb4','--xml','argus','-e',sql])
        tree=ET.fromstring(result)
        return [{field.attrib['name']:field.text for field in row} for row in tree.findall('row')]
    data={
        'scope':'production schema metadata only; no application rows or credentials',
        'migrations':query('SELECT version,description,success FROM flyway_schema_history ORDER BY installed_rank'),
        'columns':query('SELECT TABLE_NAME,COLUMN_NAME,COLUMN_TYPE,IS_NULLABLE,COLUMN_DEFAULT,COLUMN_KEY,EXTRA FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() ORDER BY TABLE_NAME,ORDINAL_POSITION'),
        'indexes':query('SELECT TABLE_NAME,INDEX_NAME,NON_UNIQUE,SEQ_IN_INDEX,COLUMN_NAME,COLLATION FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() ORDER BY TABLE_NAME,INDEX_NAME,SEQ_IN_INDEX'),
        'foreignKeys':query('SELECT TABLE_NAME,COLUMN_NAME,CONSTRAINT_NAME,REFERENCED_TABLE_NAME,REFERENCED_COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA=DATABASE() AND REFERENCED_TABLE_NAME IS NOT NULL ORDER BY TABLE_NAME,CONSTRAINT_NAME,ORDINAL_POSITION'),
    }
print(json.dumps(data,ensure_ascii=False,indent=2))
