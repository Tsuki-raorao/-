"""Export metadata only from an explicitly named Argus test MySQL container.

Run on the test host: sudo python3 export-test-schema.py <container>
Credentials stay inside the container environment. No application rows are read.
"""
import json
import subprocess
import sys
import xml.etree.ElementTree as ET

container = sys.argv[1]
database = sys.argv[2] if len(sys.argv) > 2 else 'argus_test_stage1'
if not container.startswith("argus-stage"):
    raise SystemExit("Only explicitly named Argus stage test containers are allowed")
if database not in ('argus_test_stage1', 'argus_test_acceptance', 'argus_test_perf'):
    raise SystemExit('Only explicitly isolated test databases are allowed')

def query(sql):
    command = ["docker", "exec", container, "sh", "-c",
               'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql -u"$MYSQL_USER" "$1" --xml -e "$2"',
               "schema-export", database, sql]
    result = subprocess.run(command, check=True, capture_output=True, text=True)
    root = ET.fromstring(result.stdout)
    return [{field.attrib["name"]: field.text for field in row} for row in root.findall("row")]

metadata = {
    "scope": "isolated test schema; metadata only",
    "migrations": query("SELECT version,description,success FROM flyway_schema_history ORDER BY installed_rank"),
    "columns": query("SELECT TABLE_NAME,COLUMN_NAME,COLUMN_TYPE,IS_NULLABLE,COLUMN_DEFAULT,COLUMN_KEY,EXTRA FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() ORDER BY TABLE_NAME,ORDINAL_POSITION"),
    "indexes": query("SELECT TABLE_NAME,INDEX_NAME,NON_UNIQUE,SEQ_IN_INDEX,COLUMN_NAME,COLLATION FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() ORDER BY TABLE_NAME,INDEX_NAME,SEQ_IN_INDEX"),
    "foreignKeys": query("SELECT TABLE_NAME,COLUMN_NAME,CONSTRAINT_NAME,REFERENCED_TABLE_NAME,REFERENCED_COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA=DATABASE() AND REFERENCED_TABLE_NAME IS NOT NULL ORDER BY TABLE_NAME,CONSTRAINT_NAME,ORDINAL_POSITION"),
}
print(json.dumps(metadata, ensure_ascii=False, indent=2))
