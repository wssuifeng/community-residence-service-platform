#!/bin/bash
set -e

# Flyway 11 checks this Performance Schema view while opening a MySQL 8.4 connection.
# The application user remains limited to its own schema plus this read-only metadata view.
mysql --protocol=socket -uroot -p"${MYSQL_ROOT_PASSWORD}" <<-EOSQL
GRANT SELECT ON performance_schema.user_variables_by_thread TO '${MYSQL_USER}'@'%';
FLUSH PRIVILEGES;
EOSQL
