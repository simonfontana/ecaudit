//**********************************************************************
// Copyright 2018 Telefonaktiebolaget LM Ericsson
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//**********************************************************************
package com.ericsson.bss.cassandra.ecaudit;

import java.util.Set;

import org.apache.cassandra.auth.DataResource;
import org.apache.cassandra.auth.IResource;
import org.apache.cassandra.auth.Permission;
import org.apache.cassandra.auth.RoleResource;
import org.apache.cassandra.cql3.CFName;
import org.apache.cassandra.cql3.CQLStatement;
import org.apache.cassandra.cql3.QueryProcessor;
import org.apache.cassandra.cql3.statements.AlterRoleStatement;
import org.apache.cassandra.cql3.statements.AuthenticationStatement;
import org.apache.cassandra.cql3.statements.CreateRoleStatement;
import org.apache.cassandra.cql3.statements.DropRoleStatement;
import org.apache.cassandra.cql3.statements.ListPermissionsStatement;
import org.apache.cassandra.cql3.statements.ListRolesStatement;
import org.apache.cassandra.cql3.statements.ModificationStatement;
import org.apache.cassandra.cql3.statements.PermissionsManagementStatement;
import org.apache.cassandra.cql3.statements.RoleManagementStatement;
import org.apache.cassandra.cql3.statements.SchemaAlteringStatement;
import org.apache.cassandra.cql3.statements.SelectStatement;
import org.apache.cassandra.cql3.statements.TruncateStatement;
import org.apache.cassandra.cql3.statements.UseStatement;
import org.apache.cassandra.service.ClientState;
import org.apache.commons.lang3.reflect.FieldUtils;

import com.ericsson.bss.cassandra.ecaudit.entry.AuditEntry;
import com.ericsson.bss.cassandra.ecaudit.entry.AuditEntry.Builder;
import com.ericsson.bss.cassandra.ecaudit.facade.CassandraAuditException;
import com.google.common.collect.ImmutableSet;

public class AuditEntryBuilderFactory
{
    // This list of predefines and immutable permission sets are injected to the audit entries
    // created in this factory. They should stay immutable since the AduitVO type and its builder
    // doesn't make copies of the sets.
    public static final Set<Permission> ALL_PERMISSIONS = Permission.ALL; // NOSONAR
    public static final Set<Permission> SELECT_PERMISSIONS = ImmutableSet.of(Permission.SELECT);
    public static final Set<Permission> MODIFY_PERMISSIONS = ImmutableSet.of(Permission.MODIFY);
    public static final Set<Permission> CAS_PERMISSIONS = ImmutableSet.of(Permission.SELECT, Permission.MODIFY);
    public static final Set<Permission> EXECUTE_PERMISSIONS = ImmutableSet.of(Permission.EXECUTE);
    public static final Set<Permission> CREATE_PERMISSIONS = ImmutableSet.of(Permission.CREATE);
    public static final Set<Permission> ALTER_PERMISSIONS = ImmutableSet.of(Permission.ALTER);
    public static final Set<Permission> DROP_PERMISSIONS = ImmutableSet.of(Permission.DROP);
    public static final Set<Permission> AUTHORIZE_PERMISSIONS = ImmutableSet.of(Permission.AUTHORIZE);

    public Builder createEntryBuilder(String operation, ClientState state)
    {
        CQLStatement statement;
        try
        {
            statement = QueryProcessor.getStatement(operation, state).statement;
        }
        catch (RuntimeException e)
        {
            // This is typically the result of a query towards a non-existing resource
            return AuditEntry.newBuilder()
                    .permissions(ALL_PERMISSIONS)
                    .resource(DataResource.root());
        }

        return createEntryBuilder(statement);
    }

    public Builder createEntryBuilder(CQLStatement statement) // NOSONAR
    {
        if (statement instanceof SelectStatement)
        {
            return createSelectEntryBuilder((SelectStatement) statement);
        }
        if (statement instanceof ModificationStatement)
        {
            return createModificationEntryBuilder((ModificationStatement) statement);
        }
        if (statement instanceof TruncateStatement)
        {
            return createTruncateEntryBuilder((TruncateStatement) statement);
        }

        if (statement instanceof UseStatement)
        {
            return createUseEntryBuilder((UseStatement) statement);
        }

        if (statement instanceof CreateRoleStatement)
        {
            return createCreateRoleEntryBuilder((CreateRoleStatement) statement);
        }
        if (statement instanceof AlterRoleStatement)
        {
            return createAlterRoleEntryBuilder((AlterRoleStatement) statement);
        }
        if (statement instanceof DropRoleStatement)
        {
            return createDropRoleEntryBuilder((DropRoleStatement) statement);
        }
        if (statement instanceof RoleManagementStatement)
        {
            return createRoleManagementEntryBuilder((RoleManagementStatement) statement);
        }

        if (statement instanceof ListRolesStatement)
        {
            return createListRolesEntryBuilder((ListRolesStatement) statement);
        }
        if (statement instanceof ListPermissionsStatement)
        {
            return createListPermissionsEntryBuilder((ListPermissionsStatement) statement);
        }
        if (statement instanceof PermissionsManagementStatement)
        {
            return createPermissionsManagementEntryBuilder((PermissionsManagementStatement) statement);
        }

        if (statement instanceof SchemaAlteringStatement)
        {
            return createSchemaAlteringEntryBuilder((SchemaAlteringStatement) statement);
        }

        return AuditEntry.newBuilder()
                .permissions(ALL_PERMISSIONS)
                .resource(DataResource.root());
    }

    public Builder createSelectEntryBuilder(SelectStatement statement)
    {
        return AuditEntry.newBuilder()
                .permissions(SELECT_PERMISSIONS)
                .resource(DataResource.table(statement.keyspace(), statement.columnFamily()));
    }

    public Builder createModificationEntryBuilder(ModificationStatement statement)
    {
        return AuditEntry.newBuilder()
                .permissions(statement.hasConditions() ? CAS_PERMISSIONS : MODIFY_PERMISSIONS)
                .resource(DataResource.table(statement.keyspace(), statement.columnFamily()));
    }

    public Builder createTruncateEntryBuilder(TruncateStatement statement)
    {
        return AuditEntry.newBuilder()
                .permissions(MODIFY_PERMISSIONS)
                .resource(DataResource.table(statement.keyspace(), statement.columnFamily()));
    }

    public Builder createUseEntryBuilder(UseStatement statement)
    {
        try
        {
            String keyspace = (String) FieldUtils.readField(statement, "keyspace", true);
            return AuditEntry.newBuilder()
                    .permissions(ALL_PERMISSIONS)
                    .resource(DataResource.keyspace(keyspace));
        }
        catch (IllegalAccessException e)
        {
            throw new CassandraAuditException("Failed to resolve resource", e);
        }
    }

    public Builder createBatchEntryBuilder()
    {
        return AuditEntry.newBuilder()
                .permissions(CAS_PERMISSIONS)
                .resource(DataResource.root());
    }

    public Builder updateBatchEntryBuilder(Builder builder, String operation, ClientState state)
    {
        CQLStatement statement;
        try
        {
            statement = QueryProcessor.getStatement(operation, state).statement;
        }
        catch (RuntimeException e)
        {
            // This is typically the result of a query towards a non-existing resource
            return builder
                    .permissions(CAS_PERMISSIONS)
                    .resource(DataResource.root());
        }

        return updateBatchEntryBuilder(builder, (ModificationStatement) statement);
    }

    public Builder updateBatchEntryBuilder(Builder builder, ModificationStatement statement)
    {
        return builder
                .permissions(statement.hasConditions() ? CAS_PERMISSIONS : MODIFY_PERMISSIONS)
                .resource(DataResource.table(statement.keyspace(), statement.columnFamily()));
    }

    public Builder createCreateRoleEntryBuilder(CreateRoleStatement statement)
    {
        return createSomeRoleEntryBuilder(statement)
                .permissions(CREATE_PERMISSIONS);
    }

    public Builder createAlterRoleEntryBuilder(AlterRoleStatement statement)
    {
        return createSomeRoleEntryBuilder(statement)
                .permissions(ALTER_PERMISSIONS);
    }

    public Builder createDropRoleEntryBuilder(DropRoleStatement statement)
    {
        return createSomeRoleEntryBuilder(statement)
                .permissions(DROP_PERMISSIONS);
    }

    public Builder createRoleManagementEntryBuilder(RoleManagementStatement statement)
    {
        return createSomeRoleEntryBuilder(statement)
                .permissions(AUTHORIZE_PERMISSIONS);
    }

    private Builder createSomeRoleEntryBuilder(AuthenticationStatement statement)
    {
        try
        {
            return AuditEntry.newBuilder()
                    .resource((IResource) FieldUtils.readField(statement, "role", true));
        }
        catch (IllegalAccessException e)
        {
            throw new CassandraAuditException("Failed to resolve resource", e);
        }
    }

    public Builder createListRolesEntryBuilder(ListRolesStatement statement)
    {
        IResource resource;
        try
        {
            resource = (IResource) FieldUtils.readField(statement, "grantee", true);
            if (resource == null)
            {
                resource = RoleResource.root();
            }
        }
        catch (IllegalAccessException e)
        {
            throw new CassandraAuditException("Failed to resolve resource", e);
        }

        return AuditEntry.newBuilder()
                .permissions(SELECT_PERMISSIONS)
                .resource(resource);
    }

    public Builder createListPermissionsEntryBuilder(ListPermissionsStatement statement)
    {
        IResource resource;
        try
        {
            resource = (IResource) FieldUtils.readField(statement, "grantee", true);
            if (resource == null)
            {
                resource = RoleResource.root();
            }
        }
        catch (IllegalAccessException e)
        {
            throw new CassandraAuditException("Failed to resolve resource", e);
        }

        return AuditEntry.newBuilder()
                .permissions(AUTHORIZE_PERMISSIONS)
                .resource(resource);
    }

    public Builder createPermissionsManagementEntryBuilder(PermissionsManagementStatement statement)
    {
        try
        {
            return AuditEntry.newBuilder()
                    .permissions(AUTHORIZE_PERMISSIONS)
                    .resource((IResource) FieldUtils.readField(statement, "resource", true));
        }
        catch (IllegalAccessException e)
        {
            throw new CassandraAuditException("Failed to resolve resource", e);
        }
    }

    public Builder createSchemaAlteringEntryBuilder(SchemaAlteringStatement statement)
    {
        CFName cfName;
        try
        {
            cfName = (CFName) FieldUtils.readField(statement, "cfName", true);
        }
        catch (IllegalAccessException e)
        {
            throw new CassandraAuditException("Failed to resolve resource", e);
        }

        return AuditEntry.newBuilder()
                .permissions(ALL_PERMISSIONS)
                .resource(toDataResource(cfName));
    }

    private DataResource toDataResource(CFName cfName)
    {
        if (cfName != null && cfName.hasKeyspace())
        {
            if (cfName.getColumnFamily() != null)
            {
                return DataResource.table(cfName.getKeyspace(), cfName.getColumnFamily());
            }
            else
            {
                return DataResource.keyspace(cfName.getKeyspace());
            }
        }
        else
        {
            return DataResource.root();
        }
    }
}
