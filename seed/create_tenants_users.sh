#!/bin/bash

#docker exec -it isaraadvance-postgres-1  psql -U postgres -d advance -c 'SELECT name FROM shared."Tenants";'
tenant_list=("tenant_a" "tenant_b" "tenant_c" "tenant_d" "tenant_e")
USER_COUNT=20

CSV_FILE="users.csv"
echo "tenantId,tenantSlug,username,password" > "$CSV_FILE"

echo "⏳ Fetching existing tenants from the database..."

db_output=$(docker exec isaraadvance-postgres-1 psql -U postgres -d advance -t -A -c 'SELECT name FROM shared."Tenants";' 2>/dev/null)

if [ -z "$db_output" ]; then
    echo "❌ Error: Could not fetch data from database. Check if the container is running."
    exit 1
fi

for tenant in "${tenant_list[@]}"; do
    if echo "$db_output" | grep -Fxq "$tenant"; then
        echo "✅ Tenant '$tenant' already exists. Skipping."
        mapfile -t user_list < <(docker exec isaraadvance-postgres-1 psql -U postgres -d advance -t -A -c "SELECT \"User\".\"userName\" FROM $tenant.\"User\";")
        for user in "${user_list[@]}"; do
            echo "User: $user"
            echo "2,$tenant,$user,pSw@27#Fr" >> "$CSV_FILE"
        done
    else
        echo "⚠️ Tenant '$tenant' is missing! Starting provisioning..."

        echo "🚀 Creating tenant: $tenant"
        cd "$(find /home -type d -name "*docker-compose" 2>/dev/null)"
        docker compose -f "docker-compose-simple.yml" exec backend ./bootstrap add-tenant --name "$tenant" --schema "$tenant"

        echo "👤 Creating $USER_COUNT admin users for $tenant..."
        for ((i=1; i<=USER_COUNT; i++)); do
            cd "$(find /home -type d -name "*docker-compose" 2>/dev/null)"
            docker compose -f "docker-compose-simple.yml" exec backend ./addadminuser -n "${tenant}_${i}" -p 'pSw@27#Fr' --schemaName "$tenant"
            echo "2,$tenant,${tenant}_${i},pSw@27#Fr" >> "$CSV_FILE"
        done

        echo "🎉 Finished provisioning for '$tenant'."
    fi
done

echo "🏁 All checks complete!"
echo "📋 User credentials exported to $CSV_FILE"
