/*
 * JBoss, Home of Professional Open Source.
 * See the COPYRIGHT.txt file distributed with this work for information
 * regarding copyright ownership.  Some portions may be licensed
 * to Red Hat, Inc. under one or more contributor license agreements.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
 * 02110-1301 USA.
 */
package org.teiid.olingo.service;

import java.net.URI;
import java.net.URISyntaxException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.apache.olingo.commons.api.edm.EdmEntityType;
import org.apache.olingo.commons.api.edm.EdmNavigationProperty;
import org.apache.olingo.commons.api.edm.EdmProperty;
import org.apache.olingo.server.api.ServiceMetadata;
import org.apache.olingo.server.api.uri.UriInfo;
import org.apache.olingo.server.api.uri.UriParameter;
import org.apache.olingo.server.api.uri.UriResourceEntitySet;
import org.apache.olingo.server.api.uri.UriResourceNavigation;
import org.apache.olingo.server.core.RequestURLHierarchyVisitor;
import org.apache.olingo.server.core.uri.parser.UriParserException;
import org.teiid.core.TeiidException;
import org.teiid.metadata.Column;
import org.teiid.metadata.ForeignKey;
import org.teiid.metadata.KeyRecord;
import org.teiid.metadata.MetadataStore;
import org.teiid.metadata.Table;
import org.teiid.olingo.LiteralParser;
import org.teiid.olingo.api.SQLParameter;
import org.teiid.query.sql.lang.Criteria;
import org.teiid.query.sql.lang.Update;
import org.teiid.query.sql.symbol.Constant;
import org.teiid.query.sql.symbol.ElementSymbol;
import org.teiid.query.sql.symbol.GroupSymbol;
import org.teiid.query.sql.symbol.Reference;

public class ReferenceUpdateSQLBuilder  extends RequestURLHierarchyVisitor {
    private final MetadataStore metadata;
    private String baseURI;
    private ServiceMetadata serviceMetadata;
    
    private ScopedTable updateTable;
    private ScopedTable referenceTable;
    private boolean collection;
    private final ArrayList<SQLParameter> params = new ArrayList<SQLParameter>();

    static class ScopedTable {
        private Table table;
        private EdmEntityType type;
        private List<UriParameter> keys;
        private ForeignKey fk;
        
        public ScopedTable (Table table, EdmEntityType type, List<UriParameter> keys) {
            this.table = table;
            this.type = type;
            this.keys = keys;
        }
        public Table getTable() {
            return table;
        }
        public EdmEntityType getType() {
            return type;
        }        
        public ForeignKey getFk() {
            return fk;
        }
        public void setFk(ForeignKey fk) {
            this.fk = fk;
        }
        public List<UriParameter> getKeys() {
            return keys;
        }
        public void setKeys(List<UriParameter> keys) {
            this.keys = keys;
        }        
    }
    
    public ReferenceUpdateSQLBuilder(MetadataStore metadata, String baseURI, ServiceMetadata serviceMetadata) {
        this.metadata = metadata;
        this.baseURI = baseURI;
        this.serviceMetadata = serviceMetadata;        
    }

    @Override
    public void visit(UriResourceEntitySet info) {
        Table table = ODataSQLBuilder.findTable(info.getEntitySet(), this.metadata);
        EdmEntityType type = info.getEntitySet().getEntityType();
        List<UriParameter> keys = info.getKeyPredicates();
        this.updateTable = new ScopedTable(table, type, keys);
    }
    
    @Override
    public void visit(UriResourceNavigation info) {
        EdmNavigationProperty property = info.getProperty();
        
        this.referenceTable = new ScopedTable(ODataSQLBuilder.findTable(property.getType(), 
                this.metadata), property.getType(),
                info.getKeyPredicates());
        
        if (property.isCollection()) {
            ForeignKey fk = ODataSQLBuilder.joinFK(referenceTable.table, this.updateTable.table);
            referenceTable.setFk(fk);
            
            ScopedTable temp = this.updateTable;
            this.updateTable = referenceTable;
            this.referenceTable = temp;
            this.collection = true;
        }
        else {
            ForeignKey fk = ODataSQLBuilder.joinFK(this.updateTable.table, referenceTable.table);
            this.updateTable.setFk(fk);
        }
    }    
    
    
    public Update updateReference(URI referenceId, boolean prepared, boolean delete) throws SQLException {
        try {
            if (referenceId != null) {
                UriInfo uriInfo = ODataSQLBuilder.buildUriInfo(referenceId, this.baseURI, this.serviceMetadata);
                UriResourceEntitySet uriEnitytSet = (UriResourceEntitySet)uriInfo.asUriInfoResource().getUriResourceParts().get(0);
                if (this.collection) {
                    this.updateTable.setKeys(uriEnitytSet.getKeyPredicates());
                }
                else {
                    this.referenceTable.setKeys(uriEnitytSet.getKeyPredicates());
                }
            }
        } catch (UriParserException e) {
            throw new SQLException(e);
        } catch (URISyntaxException e) {
            throw new SQLException(e);
        }
        
        try {
            GroupSymbol tableGroup = new GroupSymbol(this.updateTable.getTable().getFullName());
            Update update = new Update();
            update.setGroup(tableGroup);
            
            List<String> columnNames = ODataSQLBuilder.getColumnNames(this.updateTable.getFk().getColumns());
            for (int i = 0; i < columnNames.size(); i++) {
                Column column = this.updateTable.getFk().getColumns().get(i);
                String columnName = columnNames.get(i);
                ElementSymbol symbol = new ElementSymbol(columnName, tableGroup);
                
                EdmEntityType entityType = this.updateTable.getType();
                EdmProperty edmProperty = (EdmProperty)entityType.getProperty(columnName);
                
                // reference table keys will be null for delete scenario
                Object value = null;
                if (!delete) {
                    UriParameter parameter = getParameter(this.updateTable.getFk().getReferenceColumns().get(i),
                            this.referenceTable.getKeys());
                    value = LiteralParser.parseLiteral(edmProperty, column.getJavaType(), parameter.getText());
                }
                
                if (prepared) {
                    update.addChange(symbol, new Reference(i++));
                    this.params.add(ODataSQLBuilder.asParam(edmProperty, value));
                }
                else {
                    update.addChange(symbol, new Constant(ODataSQLBuilder.asParam(edmProperty, value).getValue()));
                }
            }
            
            KeyRecord pk = this.updateTable.getTable().getPrimaryKey();
            if (pk == null) {
                pk = this.updateTable.getTable().getUniqueKeys().get(0);
            }
            
            Criteria criteria = ODataSQLBuilder.buildEntityKeyCriteria(
                    this.updateTable.getTable(), 
                    tableGroup,
                    this.updateTable.getKeys(), 
                    null, this.metadata, null);
            update.setCriteria(criteria);
            
            return update;
        } catch (TeiidException e) {
            throw new SQLException(e);
        }
    }    
    
    private UriParameter getParameter(String name, List<UriParameter> keys) {
        for (UriParameter parameter:keys) {
            String propertyName = parameter.getName();
            if (propertyName.equals(name)) {
                return parameter;
            }
        }
        return null;
    }
    
    public List<SQLParameter> getParameters(){
        return this.params;
    }    
}
