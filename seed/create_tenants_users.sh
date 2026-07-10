#!/bin/bash

# 1. Define your target tenant list here
#docker exec -it isaraadvance-postgres-1  psql -U postgres -d advance -c 'SELECT name FROM shared."Tenants";'
#tenant_list=("tenant_a" "tenant_b" "tenant_c" "tenant_d" "tenant_e")
tenant_list=("tenant_a" "tenant_b" "tenant_c")

echo "⏳ Fetching existing tenants from the database..."

# 2. Query the database and clean up the output (removes headers and whitespace)
db_output=$(docker exec isaraadvance-postgres-1 psql -U postgres -d advance -t -A -c 'SELECT name FROM shared."Tenants";' 2>/dev/null)

if [ -z "$db_output" ]; then
    echo "❌ Error: Could not fetch data from database. Check if the container is running."
    exit 1
fi

# 3. Loop through your defined tenant list
for tenant in "${tenant_list[@]}"; do

    # Check if the tenant exists in the DB output (exact match per line)
    if echo "$db_output" | grep -Fxq "$tenant"; then
        echo "✅ Tenant '$tenant' already exists. Skipping."
    else
        echo "⚠️ Tenant '$tenant' is missing! Starting provisioning..."

        # 4. Create the missing tenant
        echo "🚀 Creating tenant: $tenant"
        cd "$(find /home -type d -name "*docker-compose" 2>/dev/null)"
        docker compose -f "docker-compose-simple.yml" exec backend ./bootstrap add-tenant --name "$tenant" --schema "$tenant"

        # 5. Run the admin user creation loop 20 times
        echo "👤 Creating 5 admin users for $tenant..."
        for i in {1..5}; do
            # Note: The query requested using the literal string 'cobschemaname' for --schemaName
            cd "$(find /home -type d -name "*docker-compose" 2>/dev/null)"
            docker compose -f "docker-compose-simple.yml" exec backend ./addadminuser -n "${tenant}_${i}" -p 'pSw@27#Fr' --schemaName "$tenant"
        done

        echo "🎉 Finished provisioning for '$tenant'."
    fi
done

echo "🏁 All checks complete!"
