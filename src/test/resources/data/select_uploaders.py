### RUN COMMNAD: python3 select_uploaders.py

import csv, math
from collections import defaultdict

rows_by_tenant = defaultdict(list)
with open("users.csv") as f:
    reader = csv.DictReader(f)
    fieldnames = reader.fieldnames
    for row in reader:
        rows_by_tenant[row["tenant_name"]].append(row)

selected = []
for tenant, rows in rows_by_tenant.items():
    n = math.ceil(0.25 * len(rows))
    selected.extend(rows[:n])

with open("uploaders.csv", "w", newline="") as f:
    writer = csv.DictWriter(f, fieldnames=fieldnames)
    writer.writeheader()
    writer.writerows(selected)

print(f"{len(selected)} uploaders selected across {len(rows_by_tenant)} tenants")