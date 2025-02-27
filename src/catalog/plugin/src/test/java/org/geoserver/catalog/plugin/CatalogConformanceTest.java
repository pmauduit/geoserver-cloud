/*
 * (c) 2014 - 2015 Open Source Geospatial Foundation - all rights reserved (c) 2001 - 2013 OpenPlans
 * This code is licensed under the GPL 2.0 license, available at the root application directory.
 */
package org.geoserver.catalog.plugin;

import static com.google.common.collect.Sets.newHashSet;

import static org.geoserver.catalog.Predicates.acceptAll;
import static org.geoserver.catalog.Predicates.asc;
import static org.geoserver.catalog.Predicates.contains;
import static org.geoserver.catalog.Predicates.desc;
import static org.geoserver.catalog.Predicates.equal;
import static org.geoserver.catalog.Predicates.or;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

import static java.lang.String.format;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import org.geoserver.catalog.Catalog;
import org.geoserver.catalog.CatalogException;
import org.geoserver.catalog.CatalogFactory;
import org.geoserver.catalog.CatalogInfo;
import org.geoserver.catalog.CatalogTestData;
import org.geoserver.catalog.CoverageInfo;
import org.geoserver.catalog.CoverageStoreInfo;
import org.geoserver.catalog.DataLinkInfo;
import org.geoserver.catalog.DataStoreInfo;
import org.geoserver.catalog.FeatureTypeInfo;
import org.geoserver.catalog.Keyword;
import org.geoserver.catalog.LayerGroupInfo;
import org.geoserver.catalog.LayerInfo;
import org.geoserver.catalog.MetadataLinkInfo;
import org.geoserver.catalog.MetadataMap;
import org.geoserver.catalog.NamespaceInfo;
import org.geoserver.catalog.Predicates;
import org.geoserver.catalog.PublishedInfo;
import org.geoserver.catalog.ResourceInfo;
import org.geoserver.catalog.SLDHandler;
import org.geoserver.catalog.StoreInfo;
import org.geoserver.catalog.StyleHandler;
import org.geoserver.catalog.StyleInfo;
import org.geoserver.catalog.WMSLayerInfo;
import org.geoserver.catalog.WMSStoreInfo;
import org.geoserver.catalog.WMTSLayerInfo;
import org.geoserver.catalog.WMTSStoreInfo;
import org.geoserver.catalog.WorkspaceInfo;
import org.geoserver.catalog.event.CatalogAddEvent;
import org.geoserver.catalog.event.CatalogListener;
import org.geoserver.catalog.event.CatalogModifyEvent;
import org.geoserver.catalog.event.CatalogPostModifyEvent;
import org.geoserver.catalog.event.CatalogRemoveEvent;
import org.geoserver.catalog.impl.CatalogImpl;
import org.geoserver.catalog.impl.CatalogPropertyAccessor;
import org.geoserver.catalog.impl.CoverageInfoImpl;
import org.geoserver.catalog.impl.NamespaceInfoImpl;
import org.geoserver.catalog.impl.RunnerBase;
import org.geoserver.catalog.impl.WorkspaceInfoImpl;
import org.geoserver.catalog.util.CloseableIterator;
import org.geoserver.config.GeoServerDataDirectory;
import org.geoserver.ows.util.OwsUtils;
import org.geoserver.platform.GeoServerExtensions;
import org.geoserver.platform.GeoServerExtensionsHelper;
import org.geoserver.platform.GeoServerResourceLoader;
import org.geoserver.security.AccessMode;
import org.geoserver.security.SecuredResourceNameChangeListener;
import org.geoserver.security.impl.DataAccessRule;
import org.geoserver.security.impl.DataAccessRuleDAO;
import org.geotools.factory.CommonFactoryFinder;
import org.geotools.util.logging.Logging;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.opengis.filter.Filter;
import org.opengis.filter.FilterFactory;
import org.opengis.filter.MultiValuedFilter.MatchAction;
import org.opengis.filter.sort.SortBy;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Initially a verbatim copy of {@code gs-main}'s {@code
 * org.geoserver.catalog.impl.CatalogImplTest}, adapted to not subclass {@code
 * GeoServerSystemTestSupport} as all that machinery is not really necessary, plus, {@code
 * GeoServerSystemTestSupport} instantiates {@code org.geoserver.catalog.impl.CatalogImpl}
 * indirectly, which defeats our purpose of testing different catalog/facade implementations with
 * this test class as a contract conformance check.
 */
public abstract class CatalogConformanceTest {

    protected CatalogImpl catalog;

    public CatalogTestData data;

    private DataAccessRuleDAO dataAccessRuleDAO;

    @TempDir public File tmpFolder;

    protected void addLayerAccessRule(
            String workspace, String layer, AccessMode mode, String... roles) throws IOException {
        DataAccessRuleDAO dao = this.dataAccessRuleDAO;
        DataAccessRule rule = new DataAccessRule();
        rule.setRoot(workspace);
        rule.setLayer(layer);
        rule.setAccessMode(mode);
        rule.getRoles().addAll(Arrays.asList(roles));
        dao.addRule(rule);
        dao.storeRules();
    }

    protected abstract CatalogImpl createCatalog();

    public static @BeforeAll void oneTimeSetup() {
        GeoServerExtensionsHelper.setIsSpringContext(false);
        if (null == GeoServerExtensions.bean("sldHandler"))
            GeoServerExtensionsHelper.singleton("sldHandler", new SLDHandler(), StyleHandler.class);
    }

    @BeforeEach
    public void setUp() throws Exception {
        catalog = createCatalog();
        catalog.setResourceLoader(new GeoServerResourceLoader());
        GeoServerDataDirectory dd = new GeoServerDataDirectory(tmpFolder);
        dataAccessRuleDAO = new DataAccessRuleDAO(dd, catalog);
        dataAccessRuleDAO.reload();

        data = CatalogTestData.empty(() -> catalog, () -> null).initialize();
    }

    protected <T extends CatalogInfo> T add(T info, Consumer<T> adder, Function<String, T> query) {
        OwsUtils.set(info, "id", null);
        assertNull(info.getId());
        adder.accept(info);
        assertNotNull(info.getId());
        String id = info.getId();
        T created = query.apply(id);
        assertNotNull(created);
        assertEquals(id, created.getId());
        return created;
    }

    protected WorkspaceInfo addWorkspace() {
        return add(data.workspaceA, catalog::add, catalog::getWorkspace);
    }

    protected NamespaceInfo addNamespace() {
        return add(data.namespaceA, catalog::add, catalog::getNamespace);
    }

    protected WorkspaceInfo addWorkspace(String name) {
        WorkspaceInfo ws2 = catalog.getFactory().createWorkspace();
        ws2.setName(name);
        catalog.add(ws2);
        ws2 = catalog.getWorkspaceByName(ws2.getName());
        return ws2;
    }

    private NamespaceInfo addNamespace(String prefix) {
        NamespaceInfo ns2 = catalog.getFactory().createNamespace();
        ns2.setPrefix(prefix);
        ns2.setURI(prefix + "_URI");
        catalog.add(ns2);
        NamespaceInfo newns = catalog.getNamespaceByPrefix(ns2.getPrefix());
        assertNotNull(newns);
        return newns;
    }

    protected DataStoreInfo addDataStore() {
        DataStoreInfo store = data.dataStoreA;
        store.setWorkspace(addWorkspace());
        return add(store, catalog::add, catalog::getDataStore);
    }

    protected CoverageStoreInfo addCoverageStore() {
        CoverageStoreInfo store = data.coverageStoreA;
        store.setWorkspace(addWorkspace());
        return add(store, catalog::add, catalog::getCoverageStore);
    }

    protected WMSStoreInfo addWMSStore() {
        WMSStoreInfo store = data.wmsStoreA;
        store.setWorkspace(addWorkspace());
        return add(store, catalog::add, id -> catalog.getStore(id, WMSStoreInfo.class));
    }

    protected WMTSStoreInfo addWMTSStore() {
        WMTSStoreInfo store = data.wmtsStoreA;
        store.setWorkspace(addWorkspace());
        return add(store, catalog::add, id -> catalog.getStore(id, WMTSStoreInfo.class));
    }

    protected FeatureTypeInfo addFeatureType() {
        FeatureTypeInfo ft = data.featureTypeA;
        ft.setStore(addDataStore());
        ft.setNamespace(addNamespace());
        return add(ft, catalog::add, catalog::getFeatureType);
    }

    protected CoverageInfo addCoverage() {
        CoverageInfo coverage = data.coverageA;
        coverage.setStore(addCoverageStore());
        coverage.setNamespace(addNamespace());
        return add(coverage, catalog::add, catalog::getCoverage);
    }

    protected WMSLayerInfo addWMSLayer() {
        WMSLayerInfo l = data.wmsLayerA;
        l.setStore(addWMSStore());
        l.setNamespace(addNamespace());
        return add(l, catalog::add, id -> catalog.getResource(id, WMSLayerInfo.class));
    }

    protected WMTSLayerInfo addWMTSLayer() {
        WMTSLayerInfo l = data.wmtsLayerA;
        l.setStore(addWMTSStore());
        l.setNamespace(addNamespace());
        return add(l, catalog::add, id -> catalog.getResource(id, WMTSLayerInfo.class));
    }

    protected StyleInfo addStyle() {
        return add(data.style1, catalog::add, catalog::getStyle);
    }

    protected StyleInfo addDefaultStyle() {
        StyleInfo defaultStyle = data.createStyle(StyleInfo.DEFAULT_LINE);
        return add(defaultStyle, catalog::add, catalog::getStyle);
    }

    protected LayerInfo addLayer() {
        LayerInfo layer = data.layerFeatureTypeA;
        layer.setResource(addFeatureType());
        layer.setDefaultStyle(addStyle());
        return add(layer, catalog::add, catalog::getLayer);
    }

    protected LayerGroupInfo addLayerGroup() {
        LayerGroupInfo lg = data.layerGroup1;
        lg.getLayers().clear();
        lg.getLayers().add(addLayer());
        return add(lg, catalog::add, catalog::getLayerGroup);
    }

    @Test
    public void testAddNamespace() {
        assertTrue(catalog.getNamespaces().isEmpty());
        catalog.add(data.namespaceA);
        assertEquals(1, catalog.getNamespaces().size());

        NamespaceInfo ns2 = catalog.getFactory().createNamespace();

        try {
            catalog.add(ns2);
            fail("adding without a prefix should throw exception");
        } catch (Exception e) {
        }

        ns2.setPrefix("ns2Prefix");
        try {
            catalog.add(ns2);
            fail("adding without a uri should throw exception");
        } catch (Exception e) {
        }

        ns2.setURI("bad uri");
        try {
            catalog.add(ns2);
            fail("adding an invalid uri should throw exception");
        } catch (Exception e) {
        }

        ns2.setURI("ns2URI");

        try {
            catalog.getNamespaces().add(ns2);
            fail("adding directly should throw an exception");
        } catch (Exception e) {
        }

        catalog.add(ns2);
    }

    @Test
    public void testAddIsolatedNamespace() {
        // create non isolated namespace
        NamespaceInfoImpl namespace1 = new NamespaceInfoImpl();
        namespace1.setPrefix("isolated_namespace_1");
        namespace1.setURI("http://www.isolated_namespace.com");
        // create isolated namespace with the same URI
        NamespaceInfoImpl namespace2 = new NamespaceInfoImpl();
        namespace2.setPrefix("isolated_namespace_2");
        namespace2.setURI("http://www.isolated_namespace.com");
        namespace2.setIsolated(true);
        try {
            // add the namespaces to the catalog
            catalog.add(namespace1);
            catalog.add(namespace2);
            // retrieve the non isolated namespace by prefix
            NamespaceInfo foundNamespace1 = catalog.getNamespaceByPrefix("isolated_namespace_1");
            assertThat(foundNamespace1.getPrefix(), is("isolated_namespace_1"));
            assertThat(foundNamespace1.getURI(), is("http://www.isolated_namespace.com"));
            assertThat(foundNamespace1.isIsolated(), is(false));
            // retrieve the isolated namespace by prefix
            NamespaceInfo foundNamespace2 = catalog.getNamespaceByPrefix("isolated_namespace_2");
            assertThat(foundNamespace2.getPrefix(), is("isolated_namespace_2"));
            assertThat(foundNamespace2.getURI(), is("http://www.isolated_namespace.com"));
            assertThat(foundNamespace2.isIsolated(), is(true));
            // retrieve the namespace by URI, the non isolated one should be returned
            NamespaceInfo foundNamespace3 =
                    catalog.getNamespaceByURI("http://www.isolated_namespace.com");
            assertThat(foundNamespace3.getPrefix(), is("isolated_namespace_1"));
            assertThat(foundNamespace3.getURI(), is("http://www.isolated_namespace.com"));
            assertThat(foundNamespace3.isIsolated(), is(false));
            // remove the non isolated namespace
            catalog.remove(foundNamespace1);
            // retrieve the namespace by URI, NULL should be returned
            NamespaceInfo foundNamespace4 =
                    catalog.getNamespaceByURI("http://www.isolated_namespace.com");
            assertThat(foundNamespace4, nullValue());
        } finally {
            // remove the namespaces
            catalog.remove(namespace1);
            catalog.remove(namespace2);
        }
    }

    @Test
    public void testRemoveNamespace() {
        catalog.add(data.namespaceA);
        assertEquals(1, catalog.getNamespaces().size());

        try {
            assertFalse(catalog.getNamespaces().remove(data.namespaceA));
            fail("removing directly should throw an exception");
        } catch (Exception e) {
        }

        catalog.remove(data.namespaceA);
        assertTrue(catalog.getNamespaces().isEmpty());
    }

    @Test
    public void testGetNamespaceById() {
        catalog.add(data.namespaceA);
        NamespaceInfo ns2 = catalog.getNamespace(data.namespaceA.getId());

        assertNotNull(ns2);
        assertNotSame(data.namespaceA, ns2);
        assertEquals(data.namespaceA, ns2);
    }

    @Test
    public void testGetNamespaceByPrefix() {
        catalog.add(data.namespaceA);

        NamespaceInfo ns2 = catalog.getNamespaceByPrefix(data.namespaceA.getPrefix());
        assertNotNull(ns2);
        assertNotSame(data.namespaceA, ns2);
        assertEquals(data.namespaceA, ns2);

        NamespaceInfo ns3 = catalog.getNamespaceByPrefix(null);
        assertNotNull(ns3);
        assertNotSame(data.namespaceA, ns3);
        assertEquals(data.namespaceA, ns3);

        NamespaceInfo ns4 = catalog.getNamespaceByPrefix(Catalog.DEFAULT);
        assertNotNull(ns4);
        assertNotSame(data.namespaceA, ns4);
        assertEquals(data.namespaceA, ns4);
    }

    @Test
    public void testGetNamespaceByURI() {
        catalog.add(data.namespaceA);
        NamespaceInfo ns2 = catalog.getNamespaceByURI(data.namespaceA.getURI());

        assertNotNull(ns2);
        assertNotSame(data.namespaceA, ns2);
        assertEquals(data.namespaceA, ns2);
    }

    @Test
    public void testSetDefaultNamespaceInvalid() {
        try {
            catalog.setDefaultNamespace(data.namespaceA);
            fail("Default namespace must exist in catalog");
        } catch (IllegalArgumentException e) {
            assertEquals("No such namespace: 'wsName'", e.getMessage());
        }
    }

    @Test
    public void testModifyNamespace() {
        catalog.add(data.namespaceA);

        NamespaceInfo ns2 = catalog.getNamespaceByPrefix(data.namespaceA.getPrefix());
        ns2.setPrefix(null);
        ns2.setURI(null);

        try {
            catalog.save(ns2);
            fail("setting prefix to null should throw exception");
        } catch (Exception e) {
        }

        ns2.setPrefix("ns2Prefix");
        try {
            catalog.save(ns2);
            fail("setting uri to null should throw exception");
        } catch (Exception e) {
        }

        ns2.setURI("ns2URI");

        NamespaceInfo ns3 = catalog.getNamespaceByPrefix(data.namespaceA.getPrefix());
        assertEquals(data.namespaceA.getPrefix(), ns3.getPrefix());
        assertEquals(data.namespaceA.getURI(), ns3.getURI());

        catalog.save(ns2);
        // ns3 = catalog.getNamespaceByPrefix(ns.getPrefix());
        ns3 = catalog.getNamespaceByPrefix("ns2Prefix");
        assertEquals(ns2, ns3);
        assertEquals("ns2Prefix", ns3.getPrefix());
        assertEquals("ns2URI", ns3.getURI());
    }

    @Test
    public void testNamespaceEvents() {
        TestListener l = new TestListener();
        catalog.addListener(l);

        NamespaceInfo ns = catalog.getFactory().createNamespace();
        ns.setPrefix("ns2Prefix");
        ns.setURI("ns2URI");

        assertTrue(l.added.isEmpty());
        assertTrue(l.modified.isEmpty());
        catalog.add(ns);
        assertEquals(1, l.added.size());
        assertEquals(ns, l.added.get(0).getSource());
        assertEquals(1, l.modified.size());
        assertEquals(catalog, l.modified.get(0).getSource());
        assertEquals("defaultNamespace", l.modified.get(0).getPropertyNames().get(0));
        assertEquals(1, l.postModified.size());
        assertEquals(catalog, l.postModified.get(0).getSource());
        assertEquals("defaultNamespace", l.postModified.get(0).getPropertyNames().get(0));

        ns = catalog.getNamespaceByPrefix("ns2Prefix");
        ns.setURI("changed");
        catalog.save(ns);

        assertEquals(2, l.modified.size());
        assertEquals(1, l.modified.get(1).getPropertyNames().size());
        assertTrue(l.modified.get(1).getPropertyNames().get(0).equalsIgnoreCase("uri"));
        assertTrue(l.modified.get(1).getOldValues().contains("ns2URI"));
        assertTrue(l.modified.get(1).getNewValues().contains("changed"));
        assertEquals(2, l.postModified.size());
        assertEquals(1, l.postModified.get(1).getPropertyNames().size());
        assertTrue(l.postModified.get(1).getPropertyNames().get(0).equalsIgnoreCase("uri"));
        assertTrue(l.postModified.get(1).getOldValues().contains("ns2URI"));
        assertTrue(l.postModified.get(1).getNewValues().contains("changed"));

        assertTrue(l.removed.isEmpty());
        catalog.remove(ns);
        assertEquals(1, l.removed.size());
        assertEquals(ns, l.removed.get(0).getSource());
    }

    @Test
    public void testAddWorkspace() {
        assertTrue(catalog.getWorkspaces().isEmpty());
        catalog.add(data.workspaceA);
        assertEquals(1, catalog.getWorkspaces().size());

        WorkspaceInfo ws2 = catalog.getFactory().createWorkspace();

        try {
            catalog.getWorkspaces().add(ws2);
            fail("adding directly should throw an exception");
        } catch (Exception e) {
        }

        try {
            catalog.add(ws2);
            fail("addign without a name should throw an exception");
        } catch (Exception e) {
        }

        ws2.setName("ws2");
        catalog.add(ws2);
    }

    @Test
    public void testRemoveWorkspace() {
        catalog.add(data.workspaceA);
        assertEquals(1, catalog.getWorkspaces().size());

        try {
            assertFalse(catalog.getWorkspaces().remove(data.workspaceA));
            fail("removing directly should throw an exception");
        } catch (Exception e) {
        }

        catalog.remove(data.workspaceA);
        assertTrue(catalog.getWorkspaces().isEmpty());
    }

    @Test
    public void testAddIsolatedWorkspace() {
        // create isolated workspace
        WorkspaceInfoImpl workspace = new WorkspaceInfoImpl();
        workspace.setName("isolated_workspace");
        workspace.setIsolated(true);
        try {
            // add it to the catalog
            catalog.add(workspace);
            // retrieve the isolated workspace
            WorkspaceInfo foundWorkspace = catalog.getWorkspaceByName("isolated_workspace");
            assertThat(foundWorkspace.isIsolated(), is(true));
        } finally {
            // remove the isolated workspace
            catalog.remove(workspace);
        }
    }

    @Test
    public void testAutoSetDefaultWorkspace() {
        catalog.add(data.workspaceA);
        assertEquals(1, catalog.getWorkspaces().size());
        assertEquals(data.workspaceA, catalog.getDefaultWorkspace());
        assertNull(catalog.getDefaultNamespace());
    }

    @Test
    public void testRemoveDefaultWorkspace() {
        catalog.add(data.workspaceA);
        assertNotNull(catalog.getDefaultWorkspace());
        catalog.remove(data.workspaceA);
        assertNull(catalog.getDefaultWorkspace());
    }

    @Test
    public void testAutoCascadeDefaultWorksapce() {
        CatalogFactory factory = catalog.getFactory();
        WorkspaceInfo ws1 = factory.createWorkspace();
        ws1.setName("ws1Name");
        WorkspaceInfo ws2 = factory.createWorkspace();
        ws2.setName("ws2Name");
        catalog.add(ws1);
        catalog.add(ws2);
        assertEquals(ws1, catalog.getDefaultWorkspace());
        catalog.remove(ws1);
        assertEquals(ws2, catalog.getDefaultWorkspace());
    }

    @Test
    public void testAutoSetDefaultNamespace() {
        catalog.add(data.namespaceA);
        assertEquals(1, catalog.getNamespaces().size());
        assertEquals(data.namespaceA, catalog.getDefaultNamespace());
    }

    @Test
    public void testRemoveDefaultNamespace() {
        catalog.add(data.namespaceA);
        catalog.remove(data.namespaceA);
        assertNull(catalog.getDefaultNamespace());
    }

    @Test
    public void testAutoCascadeDefaultNamespace() {
        CatalogFactory factory = catalog.getFactory();
        NamespaceInfo ns1 = factory.createNamespace();
        ns1.setPrefix("1");
        ns1.setURI("http://www.geoserver.org/1");
        NamespaceInfo ns2 = factory.createNamespace();
        ns2.setPrefix("2");
        ns2.setURI("http://www.geoserver.org/2");
        catalog.add(ns1);
        catalog.add(ns2);
        assertEquals(ns1, catalog.getDefaultNamespace());
        catalog.remove(ns1);
        assertEquals(ns2, catalog.getDefaultNamespace());
    }

    @Test
    public void testAutoSetDefaultStore() {
        catalog.add(data.workspaceA);
        catalog.add(data.dataStoreA);
        assertEquals(1, catalog.getDataStores().size());
        assertEquals(data.dataStoreA, catalog.getDefaultDataStore(data.workspaceA));
    }

    @Test
    public void testRemoveDefaultStore() {
        catalog.add(data.workspaceA);
        catalog.add(data.dataStoreA);
        catalog.remove(data.dataStoreA);
        assertNull(catalog.getDefaultDataStore(data.workspaceA));
    }

    @Test
    public void testGetWorkspaceById() {
        catalog.add(data.workspaceA);
        WorkspaceInfo ws2 = catalog.getWorkspace(data.workspaceA.getId());

        assertNotNull(ws2);
        assertNotSame(data.workspaceA, ws2);
        assertEquals(data.workspaceA, ws2);
    }

    @Test
    public void testGetWorkspaceByName() {
        catalog.add(data.workspaceA);
        WorkspaceInfo ws2 = catalog.getWorkspaceByName(data.workspaceA.getName());

        assertNotNull(ws2);
        assertNotSame(data.workspaceA, ws2);
        assertEquals(data.workspaceA, ws2);

        WorkspaceInfo ws3 = catalog.getWorkspaceByName(null);
        assertNotNull(ws3);
        assertNotSame(data.workspaceA, ws3);
        assertEquals(data.workspaceA, ws3);

        WorkspaceInfo ws4 = catalog.getWorkspaceByName(Catalog.DEFAULT);
        assertNotNull(ws4);
        assertNotSame(data.workspaceA, ws4);
        assertEquals(data.workspaceA, ws4);
    }

    @Test
    public void testSetDefaultWorkspaceInvalid() {
        try {
            catalog.setDefaultWorkspace(data.workspaceA);
            fail("Default workspace must exist in catalog");
        } catch (IllegalArgumentException e) {
            assertEquals("No such workspace: 'wsName'", e.getMessage());
        }
    }

    @Test
    public void testModifyWorkspace() {
        catalog.add(data.workspaceA);

        WorkspaceInfo ws2 = catalog.getWorkspaceByName(data.workspaceA.getName());
        ws2.setName(null);
        try {
            catalog.save(ws2);
            fail("setting name to null should throw exception");
        } catch (Exception e) {
        }

        ws2.setName("ws2");

        WorkspaceInfo ws3 = catalog.getWorkspaceByName(data.workspaceA.getName());
        assertEquals("wsName", ws3.getName());

        catalog.save(ws2);
        ws3 = catalog.getWorkspaceByName(ws2.getName());
        assertEquals(ws2, ws3);
        assertEquals("ws2", ws3.getName());
    }

    @Test
    public void testWorkspaceEvents() {
        TestListener l = new TestListener();
        catalog.addListener(l);

        WorkspaceInfo ws = catalog.getFactory().createWorkspace();
        ws.setName("ws2");

        assertTrue(l.added.isEmpty());
        assertTrue(l.modified.isEmpty());
        catalog.add(ws);
        assertEquals(1, l.added.size());
        assertEquals(ws, l.added.get(0).getSource());
        assertEquals(catalog, l.modified.get(0).getSource());
        assertEquals("defaultWorkspace", l.modified.get(0).getPropertyNames().get(0));
        assertEquals(catalog, l.postModified.get(0).getSource());
        assertEquals("defaultWorkspace", l.postModified.get(0).getPropertyNames().get(0));

        ws = catalog.getWorkspaceByName("ws2");
        ws.setName("changed");

        catalog.save(ws);
        assertEquals(2, l.modified.size());
        assertTrue(l.modified.get(1).getPropertyNames().contains("name"));
        assertTrue(l.modified.get(1).getOldValues().contains("ws2"));
        assertTrue(l.modified.get(1).getNewValues().contains("changed"));
        assertTrue(l.postModified.get(1).getPropertyNames().contains("name"));
        assertTrue(l.postModified.get(1).getOldValues().contains("ws2"));
        assertTrue(l.postModified.get(1).getNewValues().contains("changed"));

        assertTrue(l.removed.isEmpty());
        catalog.remove(ws);
        assertEquals(1, l.removed.size());
        assertEquals(ws, l.removed.get(0).getSource());
    }

    @Test
    public void testAddDataStore() {
        assertTrue(catalog.getDataStores().isEmpty());

        data.dataStoreA.setWorkspace(null);
        try {
            catalog.add(data.dataStoreA);
            fail("adding with no workspace should throw exception");
        } catch (Exception e) {
        }

        data.dataStoreA.setWorkspace(data.workspaceA);
        catalog.add(data.workspaceA);
        catalog.add(data.dataStoreA);

        assertEquals(1, catalog.getDataStores().size());

        DataStoreInfo retrieved = catalog.getDataStore(data.dataStoreA.getId());
        assertNotNull(retrieved);
        assertSame(catalog, retrieved.getCatalog());

        DataStoreInfo ds2 = catalog.getFactory().createDataStore();
        try {
            catalog.add(ds2);
            fail("adding without a name should throw exception");
        } catch (Exception e) {
        }

        ds2.setName("ds2Name");
        try {
            catalog.getDataStores().add(ds2);
            fail("adding directly should throw an exception");
        } catch (Exception e) {
        }

        ds2.setWorkspace(data.workspaceA);

        catalog.add(ds2);
        assertEquals(2, catalog.getDataStores().size());
    }

    @Test
    public void testAddDataStoreDefaultWorkspace() {
        catalog.add(data.workspaceA);
        catalog.setDefaultWorkspace(data.workspaceA);

        DataStoreInfo ds2 = catalog.getFactory().createDataStore();
        ds2.setName("ds2Name");
        catalog.add(ds2);

        assertEquals(data.workspaceA, ds2.getWorkspace());
    }

    @Test
    public void testRemoveDataStore() {
        addDataStore();
        assertEquals(1, catalog.getDataStores().size());

        try {
            assertFalse(catalog.getDataStores().remove(data.dataStoreA));
            fail("removing directly should throw an exception");
        } catch (Exception e) {
        }

        catalog.remove(data.dataStoreA);
        assertTrue(catalog.getDataStores().isEmpty());
    }

    @Test
    public void testGetDataStoreById() {
        addDataStore();

        DataStoreInfo ds2 = catalog.getDataStore(data.dataStoreA.getId());
        assertNotNull(ds2);
        assertNotSame(data.dataStoreA, ds2);
        assertEquals(data.dataStoreA, ds2);
        assertSame(catalog, ds2.getCatalog());
    }

    @Test
    public void testGetDataStoreByName() {
        addDataStore();

        DataStoreInfo ds2 = catalog.getDataStoreByName(data.dataStoreA.getName());
        assertNotNull(ds2);
        assertNotSame(data.dataStoreA, ds2);
        assertEquals(data.dataStoreA, ds2);
        assertSame(catalog, ds2.getCatalog());

        DataStoreInfo ds3 = catalog.getDataStoreByName(data.workspaceA, null);
        assertNotNull(ds3);
        assertNotSame(data.dataStoreA, ds3);
        assertEquals(data.dataStoreA, ds3);

        DataStoreInfo ds4 = catalog.getDataStoreByName(data.workspaceA, Catalog.DEFAULT);
        assertNotNull(ds4);
        assertNotSame(data.dataStoreA, ds4);
        assertEquals(data.dataStoreA, ds4);

        DataStoreInfo ds5 = catalog.getDataStoreByName(Catalog.DEFAULT, Catalog.DEFAULT);
        assertNotNull(ds5);
        assertNotSame(data.dataStoreA, ds5);
        assertEquals(data.dataStoreA, ds5);
    }

    @Test
    public void testGetStoreByName() {
        addDataStore();

        StoreInfo ds2 = catalog.getStoreByName(data.dataStoreA.getName(), StoreInfo.class);
        assertNotNull(ds2);
        assertNotSame(data.dataStoreA, ds2);
        assertEquals(data.dataStoreA, ds2);
        assertSame(catalog, ds2.getCatalog());

        StoreInfo ds3 = catalog.getStoreByName(data.workspaceA, null, StoreInfo.class);
        assertNotNull(ds3);
        assertNotSame(data.dataStoreA, ds3);
        assertEquals(data.dataStoreA, ds3);

        StoreInfo ds4 = catalog.getStoreByName(data.workspaceA, Catalog.DEFAULT, StoreInfo.class);
        assertNotNull(ds4);
        assertNotSame(data.dataStoreA, ds4);
        assertEquals(data.dataStoreA, ds4);

        StoreInfo ds5 = catalog.getStoreByName(Catalog.DEFAULT, Catalog.DEFAULT, StoreInfo.class);
        assertNotNull(ds5);
        assertNotSame(data.dataStoreA, ds5);
        assertEquals(data.dataStoreA, ds5);

        StoreInfo ds6 = catalog.getStoreByName((String) null, null, StoreInfo.class);
        assertNotNull(ds6);
        assertNotSame(data.dataStoreA, ds6);
        assertEquals(data.dataStoreA, ds3);

        StoreInfo ds7 = catalog.getStoreByName(Catalog.DEFAULT, Catalog.DEFAULT, StoreInfo.class);
        assertNotNull(ds7);
        assertNotSame(data.dataStoreA, ds7);
        assertEquals(ds6, ds7);
    }

    @Test
    public void testModifyDataStore() {
        addDataStore();

        DataStoreInfo ds2 = catalog.getDataStoreByName(data.dataStoreA.getName());
        ds2.setName("dsName2");
        ds2.setDescription("dsDescription2");

        DataStoreInfo ds3 = catalog.getDataStoreByName(data.dataStoreA.getName());
        assertEquals("dsName", ds3.getName());
        assertEquals("dsDescription", ds3.getDescription());

        catalog.save(ds2);
        ds3 = catalog.getDataStoreByName("dsName2");
        assertEquals(ds2, ds3);
        assertEquals("dsName2", ds3.getName());
        assertEquals("dsDescription2", ds3.getDescription());
    }

    @Test
    public void testChangeDataStoreWorkspace_no_resources() throws Exception {
        addDataStore();
        testChangeStoreWorkspace(data.dataStoreA);
    }

    @Test
    public void testChangeDataStoreWorkspaceUpdatesResourcesNamespace() throws Exception {
        addFeatureType();
        DataStoreInfo store = data.dataStoreA;
        StoreInfo updated = testChangeStoreWorkspace(store);
        verifyNamespaceOfStoreResources(updated);
    }

    @Test
    public void testChangeCoverageStoreWorkspaceUpdatesResourcesNamespace() throws Exception {
        addCoverage();
        StoreInfo store = data.coverageStoreA;
        StoreInfo updated = testChangeStoreWorkspace(store);
        verifyNamespaceOfStoreResources(updated);
    }

    @Test
    public void testChangeWMSStoreWorkspaceUpdatesResourcesNamespace() throws Exception {
        addWMSLayer();
        StoreInfo store = data.wmsStoreA;
        StoreInfo updated = testChangeStoreWorkspace(store);
        verifyNamespaceOfStoreResources(updated);
    }

    @Test
    public void testChangeWTMSStoreWorkspaceUpdatesResourcesNamespace() throws Exception {
        addWMTSLayer();
        StoreInfo store = data.wmtsStoreA;
        StoreInfo updated = testChangeStoreWorkspace(store);
        verifyNamespaceOfStoreResources(updated);
    }

    @Test
    public void testChangeDataStoreWorkspace_fails_on_no_matching_namespace() throws Exception {
        addDataStore();

        WorkspaceInfo ws2 = addWorkspace("newWorkspace");

        final DataStoreInfo ds = data.dataStoreA;
        DataStoreInfo ds2 = catalog.getDataStoreByName(ds.getName());
        ds2.setWorkspace(ws2);

        IllegalArgumentException expected =
                assertThrows(IllegalArgumentException.class, () -> catalog.save(ds2));
        assertThat(
                expected.getMessage(),
                containsString(
                        "Store dsName changed from workspace wsName to newWorkspace, but namespace newWorkspace does not exist"));
    }

    @Test
    public void testSaveDataStoreRollbacksStoreWhenFailsToUpdateResourcesNamespace()
            throws Exception {
        Assumptions.assumeTrue(catalog instanceof CatalogPlugin);
        addFeatureType();

        final DataStoreInfo ds = data.dataStoreA;
        final StoreInfo store = catalog.getDataStore(ds.getId());

        final WorkspaceInfo oldWorkspace = store.getWorkspace();
        final NamespaceInfo oldNamespace = catalog.getNamespaceByPrefix(oldWorkspace.getName());

        final WorkspaceInfo newWorkspace = addWorkspace("newWorkspace");
        addNamespace(newWorkspace.getName());

        catalog = Mockito.spy((CatalogImpl) this.catalog);
        // throw once the store's been saved and its resources are about to have the new namespace
        // set
        doThrow(IllegalStateException.class)
                .when((CatalogPlugin) catalog)
                .updateNamespace(any(), any());

        final String oldName = store.getName();
        store.setName("newName");
        store.setWorkspace(newWorkspace);

        assertThrows(IllegalStateException.class, () -> catalog.save(store));

        DataStoreInfo store2 = catalog.getDataStore(ds.getId());
        assertEquals(oldName, store2.getName());
        assertEquals(oldWorkspace, store2.getWorkspace());

        List<ResourceInfo> resources = catalog.getResourcesByStore(store2, ResourceInfo.class);
        assertFalse(resources.isEmpty());
        for (ResourceInfo resource : resources) {
            assertEquals(oldNamespace, resource.getNamespace());
        }
    }

    @Test
    public void testSaveDataStoreRollbacksBothStoreAndResources() throws Exception {
        Assumptions.assumeTrue(catalog instanceof CatalogPlugin);
        addFeatureType();

        FeatureTypeInfo ft2 = catalog.getFactory().createFeatureType();
        ft2.setName("ft2Name");
        ft2.setStore(data.dataStoreA);
        ft2.setNamespace(data.namespaceA);
        catalog.add(ft2);
        ft2 = catalog.getFeatureType(ft2.getId());

        final StoreInfo store = catalog.getDataStore(data.dataStoreA.getId());

        final WorkspaceInfo oldWorkspace = store.getWorkspace();
        final NamespaceInfo oldNamespace = catalog.getNamespaceByPrefix(oldWorkspace.getName());

        final WorkspaceInfo newWorkspace = addWorkspace("newWorkspace");
        addNamespace(newWorkspace.getName());

        catalog = Mockito.spy((CatalogPlugin) catalog);
        // make sure catalog returns resources in the expected order
        //        doReturn(Arrays.asList(ft, ft2)).when(catalog).getResourcesByStore(any(), any());

        // let the first ft's namespace be updated, fail on the second
        doThrow(IllegalStateException.class)
                .when((CatalogPlugin) catalog)
                .updateNamespace(eq(ft2), any());

        final String oldName = store.getName();
        store.setName("newName");
        store.setWorkspace(newWorkspace);

        assertThrows(IllegalStateException.class, () -> catalog.save(store));

        DataStoreInfo store2 = catalog.getDataStore(data.dataStoreA.getId());
        assertEquals(oldName, store2.getName(), "store's not rolled back before throwing");
        assertEquals(
                oldWorkspace, store2.getWorkspace(), "store's not rolled back before throwing");

        List<ResourceInfo> resources = catalog.getResourcesByStore(store2, ResourceInfo.class);
        assertFalse(resources.isEmpty());
        for (ResourceInfo resource : resources) {
            assertEquals(
                    oldNamespace, resource.getNamespace(), "ft's not rolled back before throwing");
        }
    }

    private StoreInfo testChangeStoreWorkspace(StoreInfo store) throws Exception {

        WorkspaceInfo ws2 = addWorkspace("newWorkspace");
        addNamespace(ws2.getName());

        StoreInfo store2 = catalog.getStore(store.getId(), StoreInfo.class);
        store2.setWorkspace(ws2);
        catalog.save(store2);

        assertNull(catalog.getStoreByName(data.workspaceA, store2.getName(), StoreInfo.class));
        StoreInfo updated = catalog.getStoreByName(ws2, store2.getName(), StoreInfo.class);
        assertNotNull(updated);
        return updated;
    }

    private void verifyNamespaceOfStoreResources(StoreInfo store) {
        final String wsName = store.getWorkspace().getName();
        final NamespaceInfo expected = catalog.getNamespaceByPrefix(wsName);
        assertNotNull(expected, "namespace '" + wsName + "' does not exist");

        List<ResourceInfo> resources = catalog.getResourcesByStore(store, ResourceInfo.class);
        assertFalse(resources.isEmpty(), "prepare test scenario so the store has resources");

        for (ResourceInfo resource : resources) {
            NamespaceInfo actual = resource.getNamespace();
            assertNotNull(actual);
            String msg =
                    format(
                            "resource %s of store %s did not get its namespace updated",
                            resource.getName(), store.getName());
            assertEquals(expected, actual, msg);
        }
    }

    @Test
    public void testDataStoreEvents() {
        addWorkspace();

        TestListener l = new TestListener();
        catalog.addListener(l);

        assertEquals(0, l.added.size());
        catalog.add(data.dataStoreA);
        assertEquals(1, l.added.size());
        assertEquals(data.dataStoreA, l.added.get(0).getSource());
        assertEquals(1, l.modified.size());
        assertEquals(catalog, l.modified.get(0).getSource());
        assertEquals(1, l.postModified.size());
        assertEquals(catalog, l.postModified.get(0).getSource());

        DataStoreInfo ds2 = catalog.getDataStoreByName(data.dataStoreA.getName());
        ds2.setDescription("changed");

        assertEquals(1, l.modified.size());
        catalog.save(ds2);
        assertEquals(2, l.modified.size());

        CatalogModifyEvent me = l.modified.get(1);
        assertEquals(ds2, me.getSource());
        assertEquals(1, me.getPropertyNames().size());
        assertEquals("description", me.getPropertyNames().get(0));

        assertEquals(1, me.getOldValues().size());
        assertEquals(1, me.getNewValues().size());

        assertEquals("dsDescription", me.getOldValues().get(0));
        assertEquals("changed", me.getNewValues().get(0));

        CatalogPostModifyEvent pme = l.postModified.get(1);
        assertEquals(ds2, pme.getSource());
        assertEquals(1, pme.getPropertyNames().size());
        assertEquals("description", pme.getPropertyNames().get(0));

        assertEquals(1, pme.getOldValues().size());
        assertEquals(1, pme.getNewValues().size());

        assertEquals("dsDescription", pme.getOldValues().get(0));
        assertEquals("changed", pme.getNewValues().get(0));

        assertEquals(0, l.removed.size());
        catalog.remove(data.dataStoreA);

        assertEquals(1, l.removed.size());
        assertEquals(data.dataStoreA, l.removed.get(0).getSource());
    }

    @Test
    public void testAddFeatureType() {
        assertTrue(catalog.getFeatureTypes().isEmpty());

        addFeatureType();
        assertEquals(1, catalog.getFeatureTypes().size());

        FeatureTypeInfo ft2 = catalog.getFactory().createFeatureType();
        try {
            catalog.add(ft2);
            fail("adding with no name should throw exception");
        } catch (Exception e) {
        }

        ft2.setName("ft2Name");

        try {
            catalog.add(ft2);
            fail("adding with no store should throw exception");
        } catch (Exception e) {
        }

        ft2.setStore(data.dataStoreA);
        ft2.getKeywords().add(new Keyword("keyword"));

        catalog.add(ft2);
        FeatureTypeInfo retrieved = catalog.getFeatureTypeByName("ft2Name");
        assertSame(catalog, retrieved.getCatalog());

        FeatureTypeInfo ft3 = catalog.getFactory().createFeatureType();
        ft3.setName("ft3Name");
        try {
            catalog.getFeatureTypes().add(ft3);
            fail("adding directly should throw an exception");
        } catch (Exception e) {
        }
    }

    @Test
    public void testAddCoverage() {
        // set a default namespace
        assertNotNull(catalog.getCoverages());
        assertTrue(catalog.getCoverages().isEmpty());

        addCoverage();
        assertEquals(1, catalog.getCoverages().size());

        CoverageInfo cv2 = catalog.getFactory().createCoverage();
        try {
            catalog.add(cv2);
            fail("adding with no name should throw exception");
        } catch (Exception e) {
        }

        cv2.setName("cv2Name");
        try {
            catalog.add(cv2);
            fail("adding with no store should throw exception");
        } catch (Exception e) {
        }

        cv2.setStore(data.coverageStoreA);
        catalog.add(cv2);
        assertEquals(2, catalog.getCoverages().size());

        CoverageInfo fromCatalog = catalog.getCoverageByName("cv2Name");
        assertNotNull(fromCatalog);
        assertSame(catalog, fromCatalog.getCatalog());
        // ensure the collection properties are set to NullObjects and not to null
        assertNotNull(fromCatalog.getParameters());

        CoverageInfo cv3 = catalog.getFactory().createCoverage();
        cv3.setName("cv3Name");
        try {
            catalog.getCoverages().add(cv3);
            fail("adding directly should throw an exception");
        } catch (Exception e) {
        }
    }

    @Test
    public void testAddWMSLayer() {
        // set a default namespace
        assertTrue(catalog.getResources(WMSLayerInfo.class).isEmpty());
        addWMSLayer();
        assertEquals(1, catalog.getResources(WMSLayerInfo.class).size());
    }

    @Test
    public void testAddWMTSLayer() {
        assertTrue(catalog.getResources(WMTSLayerInfo.class).isEmpty());
        addWMTSLayer();
        assertEquals(1, catalog.getResources(WMTSLayerInfo.class).size());
    }

    @Test
    public void testRemoveFeatureType() {
        addFeatureType();
        assertFalse(catalog.getFeatureTypes().isEmpty());

        try {
            catalog.getFeatureTypes().remove(data.featureTypeA);
            fail("removing directly should cause exception");
        } catch (Exception e) {
        }

        catalog.remove(data.featureTypeA);
        assertTrue(catalog.getFeatureTypes().isEmpty());
    }

    @Test
    public void testRemoveWMSLayer() {
        addWMSLayer();
        assertFalse(catalog.getResources(WMSLayerInfo.class).isEmpty());

        catalog.remove(data.wmsLayerA);
        assertTrue(catalog.getResources(WMSLayerInfo.class).isEmpty());
    }

    @Test
    public void testRemoveWMTSLayer() {
        addWMTSLayer();
        assertFalse(catalog.getResources(WMTSLayerInfo.class).isEmpty());

        catalog.remove(data.wmtsLayerA);
        assertTrue(catalog.getResources(WMTSLayerInfo.class).isEmpty());
    }

    @Test
    public void testGetFeatureTypeById() {
        addFeatureType();
        FeatureTypeInfo ft2 = catalog.getFeatureType(data.featureTypeA.getId());

        assertNotNull(ft2);
        assertNotSame(data.featureTypeA, ft2);
        assertEquals(data.featureTypeA, ft2);
        assertSame(catalog, ft2.getCatalog());
    }

    @Test
    public void testGetFeatureTypeByName() {
        addFeatureType();
        FeatureTypeInfo ft2 = catalog.getFeatureTypeByName(data.featureTypeA.getName());

        assertNotNull(ft2);
        assertNotSame(data.featureTypeA, ft2);
        assertEquals(data.featureTypeA, ft2);
        assertSame(catalog, ft2.getCatalog());

        NamespaceInfo ns2 = catalog.getFactory().createNamespace();
        ns2.setPrefix("ns2Prefix");
        ns2.setURI("ns2URI");
        catalog.add(ns2);

        FeatureTypeInfo ft3 = catalog.getFactory().createFeatureType();
        ft3.setName("ft3Name");
        ft3.setStore(data.dataStoreA);
        ft3.setNamespace(ns2);
        catalog.add(ft3);

        FeatureTypeInfo ft4 = catalog.getFeatureTypeByName(ns2.getPrefix(), ft3.getName());
        assertNotNull(ft4);
        assertNotSame(ft4, ft3);
        assertEquals(ft3, ft4);

        ft4 = catalog.getFeatureTypeByName(ns2.getURI(), ft3.getName());
        assertNotNull(ft4);
        assertNotSame(ft4, ft3);
        assertEquals(ft3, ft4);
    }

    @Test
    public void testGetFeatureTypesByStore() {
        catalog.add(data.namespaceA);
        catalog.add(data.workspaceA);

        catalog.setDefaultNamespace(data.namespaceA);
        catalog.setDefaultWorkspace(data.workspaceA);

        DataStoreInfo ds1 = catalog.getFactory().createDataStore();
        ds1.setName("ds1");
        catalog.add(ds1);

        FeatureTypeInfo ft1 = catalog.getFactory().createFeatureType();
        ft1.setName("ft1");
        ft1.setStore(ds1);
        catalog.add(ft1);

        FeatureTypeInfo ft2 = catalog.getFactory().createFeatureType();
        ft2.setName("ft2");
        ft2.setStore(ds1);
        catalog.add(ft2);

        DataStoreInfo ds2 = catalog.getFactory().createDataStore();
        ds2.setName("ds2");
        catalog.add(ds2);

        FeatureTypeInfo ft3 = catalog.getFactory().createFeatureType();
        ft3.setName("ft3");
        ft3.setStore(ds2);
        catalog.add(ft3);

        List<ResourceInfo> r = catalog.getResourcesByStore(ds1, ResourceInfo.class);
        assertEquals(2, r.size());
        assertTrue(r.contains(ft1));
        assertTrue(r.contains(ft2));
        Catalog resourceCatalog = r.get(0).getCatalog();
        assertNotNull(resourceCatalog);
        assertSame(catalog, resourceCatalog);
        resourceCatalog = r.get(1).getCatalog();
        assertNotNull(resourceCatalog);
        assertSame(catalog, resourceCatalog);
    }

    @Test
    public void testModifyFeatureType() {
        addFeatureType();

        FeatureTypeInfo ft2 = catalog.getFeatureTypeByName(data.featureTypeA.getName());
        ft2.setDescription("ft2Description");
        ft2.getKeywords().add(new Keyword("ft2"));

        FeatureTypeInfo ft3 = catalog.getFeatureTypeByName(data.featureTypeA.getName());
        assertEquals("ftName", ft3.getName());
        assertEquals("ftDescription", ft3.getDescription());
        assertTrue(ft3.getKeywords().isEmpty());

        catalog.save(ft2);
        ft3 = catalog.getFeatureTypeByName(data.featureTypeA.getName());
        assertEquals(ft2, ft3);
        assertEquals("ft2Description", ft3.getDescription());
        assertEquals(1, ft3.getKeywords().size());
    }

    @Test
    public void testModifyMetadataLinks() {
        addFeatureType();

        FeatureTypeInfo ft2 = catalog.getFeatureTypeByName(data.featureTypeA.getName());
        MetadataLinkInfo ml = catalog.getFactory().createMetadataLink();
        ml.setContent("http://www.geoserver.org/meta");
        ml.setType("text/plain");
        ml.setMetadataType("iso");
        ft2.getMetadataLinks().clear();
        ft2.getMetadataLinks().add(ml);
        catalog.save(ft2);

        FeatureTypeInfo ft3 = catalog.getFeatureTypeByName(data.featureTypeA.getName());
        MetadataLinkInfo ml3 = ft3.getMetadataLinks().get(0);
        ml3.setType("application/json");

        // do not save and grab another, the metadata link must not have been modified
        FeatureTypeInfo ft4 = catalog.getFeatureTypeByName(data.featureTypeA.getName());
        MetadataLinkInfo ml4 = ft4.getMetadataLinks().get(0);
        assertEquals("text/plain", ml4.getType());

        // now save and grab yet another, the modification must have happened
        catalog.save(ft3);
        FeatureTypeInfo ft5 = catalog.getFeatureTypeByName(data.featureTypeA.getName());
        MetadataLinkInfo ml5 = ft5.getMetadataLinks().get(0);
        assertEquals("application/json", ml5.getType());
    }

    @Test
    public void testModifyDataLinks() {
        addFeatureType();

        FeatureTypeInfo ft2 = catalog.getFeatureTypeByName(data.featureTypeA.getName());
        DataLinkInfo ml = catalog.getFactory().createDataLink();
        ml.setContent("http://www.geoserver.org/meta");
        ml.setType("text/plain");
        ft2.getDataLinks().clear();
        ft2.getDataLinks().add(ml);
        catalog.save(ft2);

        FeatureTypeInfo ft3 = catalog.getFeatureTypeByName(data.featureTypeA.getName());
        DataLinkInfo ml3 = ft3.getDataLinks().get(0);
        ml3.setType("application/json");

        // do not save and grab another, the metadata link must not have been modified
        FeatureTypeInfo ft4 = catalog.getFeatureTypeByName(data.featureTypeA.getName());
        DataLinkInfo ml4 = ft4.getDataLinks().get(0);
        assertEquals("text/plain", ml4.getType());

        // now save and grab yet another, the modification must have happened
        catalog.save(ft3);
        FeatureTypeInfo ft5 = catalog.getFeatureTypeByName(data.featureTypeA.getName());
        DataLinkInfo ml5 = ft5.getDataLinks().get(0);
        assertEquals("application/json", ml5.getType());
    }

    @Test
    public void testFeatureTypeEvents() {
        // set default namespace
        addNamespace();
        addDataStore();

        TestListener l = new TestListener();
        catalog.addListener(l);

        FeatureTypeInfo ft = catalog.getFactory().createFeatureType();
        ft.setName("ftName");
        ft.setDescription("ftDescription");
        ft.setStore(data.dataStoreA);

        assertTrue(l.added.isEmpty());
        catalog.add(ft);

        assertEquals(1, l.added.size());
        assertEquals(ft, l.added.get(0).getSource());

        ft = catalog.getFeatureTypeByName("ftName");
        ft.setDescription("changed");
        assertTrue(l.modified.isEmpty());
        catalog.save(ft);
        assertEquals(1, l.modified.size());
        assertEquals(ft, l.modified.get(0).getSource());
        assertTrue(l.modified.get(0).getPropertyNames().contains("description"));
        assertTrue(l.modified.get(0).getOldValues().contains("ftDescription"));
        assertTrue(l.modified.get(0).getNewValues().contains("changed"));
        assertEquals(1, l.modified.size());
        assertEquals(ft, l.postModified.get(0).getSource());
        assertTrue(l.postModified.get(0).getPropertyNames().contains("description"));
        assertTrue(l.postModified.get(0).getOldValues().contains("ftDescription"));
        assertTrue(l.postModified.get(0).getNewValues().contains("changed"));

        assertTrue(l.removed.isEmpty());
        catalog.remove(ft);
        assertEquals(1, l.removed.size());
        assertEquals(ft, l.removed.get(0).getSource());
    }

    @Test
    public void testModifyMetadata() {
        // set default namespace
        addNamespace();
        addDataStore();

        TestListener l = new TestListener();
        catalog.addListener(l);

        FeatureTypeInfo ft = catalog.getFactory().createFeatureType();
        ft.setName("ftName");
        ft.setDescription("ftDescription");
        ft.setStore(data.dataStoreA);

        assertTrue(l.added.isEmpty());
        catalog.add(ft);

        assertEquals(1, l.added.size());
        assertEquals(ft, l.added.get(0).getSource());

        ft = catalog.getFeatureTypeByName("ftName");
        ft.getMetadata().put("newValue", "abcd");
        MetadataMap newMetadata = new MetadataMap(ft.getMetadata());
        catalog.save(ft);
        assertEquals(1, l.modified.size());
        assertEquals(ft, l.modified.get(0).getSource());
        assertTrue(l.modified.get(0).getPropertyNames().contains("metadata"));
        assertTrue(l.modified.get(0).getOldValues().contains(new MetadataMap()));
        assertTrue(l.modified.get(0).getNewValues().contains(newMetadata));
    }

    @Test
    public void testAddLayer() {
        assertTrue(catalog.getLayers().isEmpty());
        addLayer();

        assertEquals(1, catalog.getLayers().size());

        LayerInfo l2 = catalog.getFactory().createLayer();
        try {
            catalog.add(l2);
            fail("adding with no name should throw exception");
        } catch (Exception e) {
        }

        // l2.setName( "l2" );
        try {
            catalog.add(l2);
            fail("adding with no resource should throw exception");
        } catch (Exception e) {
        }

        l2.setResource(data.featureTypeA);
        // try {
        // catalog.add( l2 );
        // fail( "adding with no default style should throw exception");
        // }
        // catch( Exception e) {}
        //
        l2.setDefaultStyle(data.style1);

        try {
            catalog.add(l2);
            fail(
                    "Adding a second layer for the same resource should throw exception, layer name is tied to resource name and would end up with two layers named the same or a broken catalog");
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("already exists"));
        }

        assertEquals(1, catalog.getLayers().size());
    }

    @Test
    public void testGetLayerById() {
        addLayer();

        LayerInfo l2 = catalog.getLayer(data.layerFeatureTypeA.getId());
        assertNotNull(l2);
        assertNotSame(data.layerFeatureTypeA, l2);
        assertEquals(data.layerFeatureTypeA, l2);
        assertSame(catalog, l2.getResource().getCatalog());
    }

    @Test
    public void testGetLayerByName() {
        addLayer();

        LayerInfo l2 = catalog.getLayerByName(data.layerFeatureTypeA.getName());
        assertNotNull(l2);
        assertNotSame(data.layerFeatureTypeA, l2);
        assertEquals(data.layerFeatureTypeA, l2);
    }

    @Test
    public void testGetLayerByNameWithoutColon() {
        // create two workspaces
        catalog.add(data.namespaceB);
        catalog.add(data.namespaceC);

        catalog.add(data.workspaceB);
        catalog.add(data.workspaceC);

        catalog.setDefaultNamespace(data.namespaceC);
        catalog.setDefaultWorkspace(data.workspaceC);

        catalog.add(data.dataStoreB);
        catalog.add(data.dataStoreC);

        // create three resources, aaa:bar, bbb:bar, aaa:bar2
        FeatureTypeInfo ftA = catalog.getFactory().createFeatureType();
        ftA.setEnabled(true);
        ftA.setName("bar");
        ftA.setAbstract("ftAbstract");
        ftA.setDescription("ftDescription");
        ftA.setStore(data.dataStoreB);
        ftA.setNamespace(data.namespaceB);

        FeatureTypeInfo ftB = catalog.getFactory().createFeatureType();
        ftB.setName("bar");
        ftB.setAbstract("ftAbstract");
        ftB.setDescription("ftDescription");
        ftB.setStore(data.dataStoreC);
        ftB.setNamespace(data.namespaceC);

        FeatureTypeInfo ftC = catalog.getFactory().createFeatureType();
        ftC.setName("bar2");
        ftC.setAbstract("ftAbstract");
        ftC.setDescription("ftDescription");
        ftC.setStore(data.dataStoreB);
        ftC.setNamespace(data.namespaceB);
        ftC.setEnabled(true);
        ftB.setEnabled(true);

        catalog.add(ftA);
        catalog.add(ftB);
        catalog.add(ftC);

        addStyle();

        LayerInfo lA = catalog.getFactory().createLayer();
        lA.setResource(ftA);
        lA.setDefaultStyle(data.style1);
        lA.setEnabled(true);

        LayerInfo lB = catalog.getFactory().createLayer();
        lB.setResource(ftB);
        lB.setDefaultStyle(data.style1);
        lB.setEnabled(true);

        LayerInfo lC = catalog.getFactory().createLayer();
        lC.setResource(ftC);
        lC.setDefaultStyle(data.style1);
        lC.setEnabled(true);

        catalog.add(lA);
        catalog.add(lB);
        catalog.add(lC);

        // this search should give us back the bar in the default worksapce
        LayerInfo searchedResult = catalog.getLayerByName("bar");
        assertNotNull(searchedResult);
        assertEquals(lB, searchedResult);

        // this search should give us back the bar in the other workspace
        searchedResult = catalog.getLayerByName("aaa:bar");
        assertNotNull(searchedResult);
        assertEquals(lA, searchedResult);

        // unqualified, it should give us the only bar2 available
        searchedResult = catalog.getLayerByName("bar2");
        assertNotNull(searchedResult);
        assertEquals(lC, searchedResult);

        // qualified should work the same
        searchedResult = catalog.getLayerByName("aaa:bar2");
        assertNotNull(searchedResult);
        assertEquals(lC, searchedResult);

        // with the wrong workspace, should give us nothing
        searchedResult = catalog.getLayerByName("bbb:bar2");
        assertNull(searchedResult);
    }

    @Test
    public void testGetLayerByNameWithColon() {
        addNamespace();
        addDataStore();
        FeatureTypeInfo ft = catalog.getFactory().createFeatureType();
        ft.setEnabled(true);
        ft.setName("foo:bar");
        ft.setAbstract("ftAbstract");
        ft.setDescription("ftDescription");
        ft.setStore(data.dataStoreA);
        ft.setNamespace(data.namespaceA);
        catalog.add(ft);

        addStyle();
        LayerInfo l = catalog.getFactory().createLayer();
        l.setResource(ft);
        l.setEnabled(true);
        l.setDefaultStyle(data.style1);

        catalog.add(l);
        assertNotNull(catalog.getLayerByName("foo:bar"));
    }

    @Test
    public void testGetLayerByResource() {
        addLayer();

        List<LayerInfo> layers = catalog.getLayers(data.featureTypeA);
        assertEquals(1, layers.size());
        LayerInfo l2 = layers.get(0);

        assertNotSame(data.layerFeatureTypeA, l2);
        assertEquals(data.layerFeatureTypeA, l2);
    }

    @Test
    public void testRemoveLayer() {
        addLayer();
        assertEquals(1, catalog.getLayers().size());

        catalog.remove(data.layerFeatureTypeA);
        assertTrue(catalog.getLayers().isEmpty());
    }

    @Test
    public void testRemoveLayerAndAssociatedDataRules() throws IOException {
        DataAccessRuleDAO dao = this.dataAccessRuleDAO;
        CatalogListener listener = new SecuredResourceNameChangeListener(catalog, dao);
        addLayer();
        assertEquals(1, catalog.getLayers().size());

        String workspaceName =
                data.layerFeatureTypeA.getResource().getStore().getWorkspace().getName();
        addLayerAccessRule(workspaceName, data.layerFeatureTypeA.getName(), AccessMode.WRITE, "*");
        assertTrue(layerHasSecurityRule(dao, workspaceName, data.layerFeatureTypeA.getName()));
        catalog.remove(data.layerFeatureTypeA);
        assertTrue(catalog.getLayers().isEmpty());
        dao.reload();
        assertFalse(layerHasSecurityRule(dao, workspaceName, data.layerFeatureTypeA.getName()));
        catalog.removeListener(listener);
    }

    private boolean layerHasSecurityRule(
            DataAccessRuleDAO dao, String workspaceName, String layerName) {

        List<DataAccessRule> rules = dao.getRules();
        for (DataAccessRule rule : rules) {
            if (rule.getRoot().equalsIgnoreCase(workspaceName)
                    && rule.getLayer().equalsIgnoreCase(layerName)) return true;
        }

        return false;
    }

    @Test
    public void testModifyLayer() {
        addLayer();

        LayerInfo l2 = catalog.getLayerByName(data.layerFeatureTypeA.getName());
        // l2.setName( null );
        l2.setResource(null);

        LayerInfo l3 = catalog.getLayerByName(data.layerFeatureTypeA.getName());
        assertEquals(data.layerFeatureTypeA.getName(), l3.getName());

        // try {
        // catalog.save(l2);
        // fail( "setting name to null should throw exception");
        // }
        // catch( Exception e ) {}
        //
        // l2.setName( "changed" );
        try {
            catalog.save(l2);
            fail("setting resource to null should throw exception");
        } catch (Exception e) {
        }

        l2.setResource(data.featureTypeA);
        catalog.save(l2);

        // TODO: reinstate with resource/publishing split done
        // l3 = catalog.getLayerByName( "changed" );
        l3 = catalog.getLayerByName(data.featureTypeA.getName());
        assertNotNull(l3);
    }

    @Test
    public void testModifyLayerDefaultStyle() {
        // create new style
        CatalogFactory factory = catalog.getFactory();
        StyleInfo s2 = factory.createStyle();
        s2.setName("styleName2");
        s2.setFilename("styleFilename2");
        catalog.add(s2);

        // change the layer style
        addLayer();
        LayerInfo l2 = catalog.getLayerByName(data.layerFeatureTypeA.getName());
        l2.setDefaultStyle(catalog.getStyleByName("styleName2"));
        catalog.save(l2);

        // get back and compare with itself
        LayerInfo l3 = catalog.getLayerByName(data.layerFeatureTypeA.getName());
        LayerInfo l4 = catalog.getLayerByName(data.layerFeatureTypeA.getName());
        assertEquals(l3, l4);
    }

    @Test
    public void testEnableLayer() {
        addLayer();

        LayerInfo l2 = catalog.getLayerByName(data.layerFeatureTypeA.getName());
        assertTrue(l2.isEnabled());
        assertTrue(l2.enabled());
        assertTrue(l2.getResource().isEnabled());

        l2.setEnabled(false);
        catalog.save(l2);
        // GR: if not saving also the associated resource, we're assuming saving the layer also
        // saves its ResourceInfo, which is wrong, but works on the in-memory catalog by accident
        catalog.save(l2.getResource());

        l2 = catalog.getLayerByName(l2.getName());
        assertFalse(l2.isEnabled());
        assertFalse(l2.enabled());
        assertFalse(l2.getResource().isEnabled());
    }

    @Test
    public void testLayerEvents() {
        addFeatureType();
        addStyle();

        TestListener tl = new TestListener();
        catalog.addListener(tl);

        assertTrue(tl.added.isEmpty());
        catalog.add(data.layerFeatureTypeA);
        assertEquals(1, tl.added.size());
        assertEquals(data.layerFeatureTypeA, tl.added.get(0).getSource());

        LayerInfo l2 = catalog.getLayerByName(data.layerFeatureTypeA.getName());
        l2.setPath("newPath");

        assertTrue(tl.modified.isEmpty());
        catalog.save(l2);
        assertEquals(1, tl.modified.size());
        assertEquals(l2, tl.modified.get(0).getSource());
        assertTrue(tl.modified.get(0).getPropertyNames().contains("path"));
        assertTrue(tl.modified.get(0).getOldValues().contains(null));
        assertTrue(tl.modified.get(0).getNewValues().contains("newPath"));
        assertEquals(1, tl.postModified.size());
        assertEquals(l2, tl.postModified.get(0).getSource());
        assertTrue(tl.postModified.get(0).getPropertyNames().contains("path"));
        assertTrue(tl.postModified.get(0).getOldValues().contains(null));
        assertTrue(tl.postModified.get(0).getNewValues().contains("newPath"));

        assertTrue(tl.removed.isEmpty());
        catalog.remove(l2);
        assertEquals(1, tl.removed.size());
        assertEquals(l2, tl.removed.get(0).getSource());
    }

    @Test
    public void testAddStyle() {
        assertTrue(catalog.getStyles().isEmpty());

        addStyle();
        assertEquals(1, catalog.getStyles().size());

        StyleInfo s2 = catalog.getFactory().createStyle();
        try {
            catalog.add(s2);
            fail("adding without name should throw exception");
        } catch (Exception e) {
        }

        s2.setName("s2Name");
        try {
            catalog.add(s2);
            fail("adding without fileName should throw exception");
        } catch (Exception e) {
        }

        s2.setFilename("s2Filename");
        try {
            catalog.getStyles().add(s2);
            fail("adding directly should throw exception");
        } catch (Exception e) {
        }

        catalog.add(s2);
        assertEquals(2, catalog.getStyles().size());
    }

    @Test
    public void testAddStyleWithNameConflict() throws Exception {
        addWorkspace();
        addStyle();

        StyleInfo s2 = catalog.getFactory().createStyle();
        s2.setName(data.style1.getName());
        s2.setFilename(data.style1.getFilename());

        try {
            catalog.add(s2);
            fail("Should have failed with existing global style with same name");
        } catch (IllegalArgumentException expected) {
        }

        List<StyleInfo> currStyles = catalog.getStyles();

        // should pass after setting workspace
        s2.setWorkspace(data.workspaceA);
        catalog.add(s2);

        assertFalse(
                new HashSet<StyleInfo>(currStyles)
                        .equals(new HashSet<StyleInfo>(catalog.getStyles())));

        StyleInfo s3 = catalog.getFactory().createStyle();
        s3.setName(s2.getName());
        s3.setFilename(s2.getFilename());

        try {
            catalog.add(s3);
            fail();
        } catch (IllegalArgumentException expected) {
        }

        s3.setWorkspace(data.workspaceA);
        try {
            catalog.add(s3);
            fail();
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void testGetStyleById() {
        addStyle();

        StyleInfo s2 = catalog.getStyle(data.style1.getId());
        assertNotNull(s2);
        assertNotSame(data.style1, s2);
        assertEquals(data.style1, s2);
    }

    @Test
    public void testGetStyleByName() {
        addStyle();

        StyleInfo s2 = catalog.getStyleByName(data.style1.getName());
        assertNotNull(s2);
        assertNotSame(data.style1, s2);
        assertEquals(data.style1, s2);
    }

    @Test
    public void testGetStyleByNameWithWorkspace() {
        addWorkspace();
        addStyle();

        StyleInfo s2 = catalog.getFactory().createStyle();
        s2.setName("styleNameWithWorkspace");
        s2.setFilename("styleFilenameWithWorkspace");
        s2.setWorkspace(data.workspaceA);
        catalog.add(s2);

        assertNotNull(catalog.getStyleByName("styleNameWithWorkspace"));
        assertNotNull(catalog.getStyleByName(data.workspaceA.getName(), "styleNameWithWorkspace"));
        assertNotNull(catalog.getStyleByName(data.workspaceA, "styleNameWithWorkspace"));
        assertNull(catalog.getStyleByName((WorkspaceInfo) null, "styleNameWithWorkspace"));

        assertNull(catalog.getStyleByName(data.workspaceA.getName(), "style1"));
        assertNull(catalog.getStyleByName(data.workspaceA, "style1"));
        assertNotNull(catalog.getStyleByName((WorkspaceInfo) null, "style1"));
    }

    @Test
    public void testGetStyleByNameWithWorkspace2() throws Exception {
        addWorkspace();

        WorkspaceInfo ws2 = catalog.getFactory().createWorkspace();
        ws2.setName("wsName2");
        catalog.add(ws2);

        // add style with same name in each workspace
        StyleInfo s1 = catalog.getFactory().createStyle();
        s1.setName("foo");
        s1.setFilename("foo1.sld");
        s1.setWorkspace(data.workspaceA);
        catalog.add(s1);

        StyleInfo s2 = catalog.getFactory().createStyle();
        s2.setName("foo");
        s2.setFilename("foo2.sld");
        s2.setWorkspace(ws2);
        catalog.add(s2);

        assertEquals(s1, catalog.getStyleByName("foo"));

        assertEquals(s1, catalog.getStyleByName(data.workspaceA.getName(), "foo"));
        assertEquals(s1, catalog.getStyleByName(data.workspaceA, "foo"));

        assertEquals(s2, catalog.getStyleByName(ws2.getName(), "foo"));
        assertEquals(s2, catalog.getStyleByName(ws2, "foo"));
    }

    @Test
    public void testGetStyles() {
        addWorkspace();
        addStyle();

        assertEquals(1, catalog.getStyles().size());
        assertEquals(0, catalog.getStylesByWorkspace(data.workspaceA.getName()).size());
        assertEquals(0, catalog.getStylesByWorkspace(data.workspaceA).size());
        assertEquals(0, catalog.getStylesByWorkspace((WorkspaceInfo) null).size());

        StyleInfo s2 = catalog.getFactory().createStyle();
        s2.setName("styleNameWithWorkspace");
        s2.setFilename("styleFilenameWithWorkspace");
        s2.setWorkspace(data.workspaceA);
        catalog.add(s2);

        assertEquals(2, catalog.getStyles().size());
        assertEquals(1, catalog.getStylesByWorkspace(data.workspaceA.getName()).size());
        assertEquals(1, catalog.getStylesByWorkspace(data.workspaceA).size());
        assertEquals(1, catalog.getStylesByWorkspace((WorkspaceInfo) null).size());
    }

    @Test
    public void testModifyStyle() {
        addStyle();

        StyleInfo s2 = catalog.getStyleByName(data.style1.getName());
        s2.setName(null);
        s2.setFilename(null);

        StyleInfo s3 = catalog.getStyleByName(data.style1.getName());
        assertEquals(data.style1, s3);

        try {
            catalog.save(s2);
            fail("setting name to null should fail");
        } catch (Exception e) {
        }

        s2.setName(data.style1.getName());
        try {
            catalog.save(s2);
            fail("setting filename to null should fail");
        } catch (Exception e) {
        }

        s2.setName("s2Name");
        s2.setFilename("s2Name.sld");
        catalog.save(s2);

        s3 = catalog.getStyleByName("style1");
        assertNull(s3);

        s3 = catalog.getStyleByName(s2.getName());
        assertEquals(s2, s3);
    }

    @Test
    public void testModifyDefaultStyle() {
        addWorkspace();
        addDefaultStyle();
        StyleInfo s = catalog.getStyleByName(StyleInfo.DEFAULT_LINE);

        s.setName("foo");

        try {
            catalog.save(s);
            fail("changing name of default style should fail");
        } catch (Exception e) {
        }

        s = catalog.getStyleByName(StyleInfo.DEFAULT_LINE);
        s.setWorkspace(data.workspaceA);
        try {
            catalog.save(s);
            fail("changing workspace of default style should fail");
        } catch (Exception e) {
        }
    }

    @Test
    public void testRemoveStyle() {
        addStyle();
        assertEquals(1, catalog.getStyles().size());

        catalog.remove(data.style1);
        assertTrue(catalog.getStyles().isEmpty());
    }

    @Test
    public void testRemoveDefaultStyle() {
        addWorkspace();
        addDefaultStyle();
        StyleInfo s = catalog.getStyleByName(StyleInfo.DEFAULT_LINE);

        try {
            catalog.remove(s);
            fail("removing default style should fail");
        } catch (Exception e) {
        }
    }

    @Test
    public void testStyleEvents() {
        TestListener l = new TestListener();
        catalog.addListener(l);

        assertTrue(l.added.isEmpty());
        catalog.add(data.style1);
        assertEquals(1, l.added.size());
        assertEquals(data.style1, l.added.get(0).getSource());

        StyleInfo s2 = catalog.getStyleByName(data.style1.getName());
        s2.setFilename("changed");

        assertTrue(l.modified.isEmpty());
        assertTrue(l.postModified.isEmpty());
        catalog.save(s2);
        assertEquals(1, l.modified.size());
        assertEquals(s2, l.modified.get(0).getSource());
        assertTrue(l.modified.get(0).getPropertyNames().contains("filename"));
        assertTrue(l.modified.get(0).getOldValues().contains("styleFilename"));
        assertTrue(l.modified.get(0).getNewValues().contains("changed"));
        assertEquals(1, l.postModified.size());
        assertEquals(s2, l.postModified.get(0).getSource());
        assertTrue(l.postModified.get(0).getPropertyNames().contains("filename"));
        assertTrue(l.postModified.get(0).getOldValues().contains("styleFilename"));
        assertTrue(l.postModified.get(0).getNewValues().contains("changed"));

        assertTrue(l.removed.isEmpty());
        catalog.remove(s2);
        assertEquals(1, l.removed.size());
        assertEquals(s2, l.removed.get(0).getSource());
    }

    @Test
    public void testProxyBehaviour() throws Exception {
        testAddLayer();

        // l = catalog.getLayerByName( "layerName");
        LayerInfo l = catalog.getLayerByName(data.featureTypeA.getName());
        assertTrue(l instanceof Proxy);

        ResourceInfo r = l.getResource();
        assertTrue(r instanceof Proxy);

        String oldName = data.featureTypeA.getName();
        r.setName("changed");
        catalog.save(r);

        assertNull(catalog.getLayerByName(oldName));
        l = catalog.getLayerByName(r.getName());
        assertNotNull(l);
        assertEquals("changed", l.getResource().getName());
    }

    @Test
    public void testProxyListBehaviour() throws Exception {
        catalog.add(data.style1);

        StyleInfo s2 = catalog.getFactory().createStyle();
        s2.setName("a" + data.style1.getName());
        s2.setFilename("a.sld");
        catalog.add(s2);

        List<StyleInfo> styles = catalog.getStyles();
        assertEquals(2, styles.size());

        // test immutability
        Comparator<StyleInfo> comparator =
                new Comparator<StyleInfo>() {

                    public int compare(StyleInfo o1, StyleInfo o2) {
                        return o1.getName().compareTo(o2.getName());
                    }
                };
        try {
            Collections.sort(styles, comparator);
            fail("Expected runtime exception, immutable collection");
        } catch (RuntimeException e) {
            assertTrue(true);
        }

        styles = new ArrayList<StyleInfo>(styles);
        Collections.sort(styles, comparator);

        assertEquals("a" + data.style1.getName(), styles.get(0).getName());
        assertEquals(data.style1.getName(), styles.get(1).getName());
    }

    @Test
    public void testExceptionThrowingListener() throws Exception {
        ExceptionThrowingListener l = new ExceptionThrowingListener();
        catalog.addListener(l);

        l.throwCatalogException = false;

        WorkspaceInfo ws = catalog.getFactory().createWorkspace();
        ws.setName("foo");

        // no exception thrown back
        catalog.add(ws);

        l.throwCatalogException = true;
        ws = catalog.getFactory().createWorkspace();
        ws.setName("bar");

        try {
            catalog.add(ws);
            fail();
        } catch (CatalogException ce) {
            // good
        }
    }

    @Test
    public void testAddWMSStore() {
        assertTrue(catalog.getStores(WMSStoreInfo.class).isEmpty());
        addWMSStore();
        assertEquals(1, catalog.getStores(WMSStoreInfo.class).size());

        WMSStoreInfo retrieved = catalog.getStore(data.wmsStoreA.getId(), WMSStoreInfo.class);
        assertNotNull(retrieved);
        assertSame(catalog, retrieved.getCatalog());

        WMSStoreInfo wms2 = catalog.getFactory().createWebMapServer();
        wms2.setName("wms2Name");
        wms2.setWorkspace(data.workspaceA);

        catalog.add(wms2);
        assertEquals(2, catalog.getStores(WMSStoreInfo.class).size());
    }

    @Test
    public void testAddWMTSStore() {
        assertTrue(catalog.getStores(WMTSStoreInfo.class).isEmpty());
        addWMTSStore();
        assertEquals(1, catalog.getStores(WMTSStoreInfo.class).size());

        WMTSStoreInfo retrieved = catalog.getStore(data.wmtsStoreA.getId(), WMTSStoreInfo.class);
        assertNotNull(retrieved);
        assertSame(catalog, retrieved.getCatalog());

        WMTSStoreInfo wmts2 = catalog.getFactory().createWebMapTileServer();
        wmts2.setName("wmts2Name");
        wmts2.setWorkspace(data.workspaceA);

        catalog.add(wmts2);
        assertEquals(2, catalog.getStores(WMTSStoreInfo.class).size());
    }

    protected int GET_LAYER_BY_ID_WITH_CONCURRENT_ADD_TEST_COUNT = 500;
    private static final int GET_LAYER_BY_ID_WITH_CONCURRENT_ADD_THREAD_COUNT = 10;

    /**
     * This test cannot work, the catalog subsystem is not thread safe, that's why we have the
     * configuration locks. Re-enable when the catalog subsystem is made thread safe.
     *
     * <p><b>NOTE</b> this actually runs now, it just takes an awful amount of time to execute.
     * Revisit.
     */
    @Test
    @Disabled
    public void testGetLayerByIdWithConcurrentAdd() throws Exception {
        addDataStore();
        addNamespace();
        addStyle();
        catalog.add(data.featureTypeA);

        LayerInfo layer = catalog.getFactory().createLayer();
        layer.setResource(data.featureTypeA);
        catalog.add(layer);
        String id = layer.getId();

        CountDownLatch ready =
                new CountDownLatch(GET_LAYER_BY_ID_WITH_CONCURRENT_ADD_THREAD_COUNT + 1);
        CountDownLatch done = new CountDownLatch(GET_LAYER_BY_ID_WITH_CONCURRENT_ADD_THREAD_COUNT);

        List<RunnerBase> runners = new ArrayList<RunnerBase>();
        for (int i = 0; i < GET_LAYER_BY_ID_WITH_CONCURRENT_ADD_THREAD_COUNT; i++) {
            RunnerBase runner = new LayerAddRunner(ready, done, i);
            new Thread(runner).start();
            runners.add(runner);
        }

        // note that test thread is ready
        ready.countDown();
        // wait for all threads to reach latch in order to maximize likelihood of contention
        ready.await();

        for (int i = 0; i < GET_LAYER_BY_ID_WITH_CONCURRENT_ADD_TEST_COUNT; i++) {
            catalog.getLayer(id);
        }

        // make sure worker threads are done
        done.await();

        RunnerBase.checkForRunnerExceptions(runners);
    }

    @Test
    public void testAddLayerGroupNameConflict() throws Exception {
        addLayerGroup();

        LayerGroupInfo lg2 = catalog.getFactory().createLayerGroup();

        lg2.setName("layerGroup");
        lg2.getLayers().add(data.layerFeatureTypeA);
        lg2.getStyles().add(data.style1);
        try {
            catalog.add(lg2);
            fail("should have failed because same name and no workspace set");
        } catch (IllegalArgumentException expected) {
        }

        // setting a workspace shluld pass
        lg2.setWorkspace(data.workspaceA);
        catalog.add(lg2);
    }

    @Test
    public void testAddLayerGroupWithWorkspaceWithResourceFromAnotherWorkspace() {
        WorkspaceInfo ws = catalog.getFactory().createWorkspace();
        ws.setName("other");
        catalog.add(ws);

        LayerGroupInfo lg2 = catalog.getFactory().createLayerGroup();
        lg2.setWorkspace(ws);
        lg2.setName("layerGroup2");
        lg2.getLayers().add(data.layerFeatureTypeA);
        lg2.getStyles().add(data.style1);
        try {
            catalog.add(lg2);
            fail();
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void testGetLayerGroupByName() {
        addLayerGroup();
        assertNotNull(catalog.getLayerGroupByName("layerGroup"));
        assertNotNull(catalog.getLayerGroupByName((WorkspaceInfo) null, "layerGroup"));
        assertNull(catalog.getLayerGroupByName(catalog.getDefaultWorkspace(), "layerGroup"));

        LayerGroupInfo lg2 = catalog.getFactory().createLayerGroup();
        lg2.setWorkspace(data.workspaceA);
        assertEquals(data.workspaceA, catalog.getDefaultWorkspace());
        lg2.setName("layerGroup2");
        lg2.getLayers().add(data.layerFeatureTypeA);
        lg2.getStyles().add(data.style1);
        catalog.add(lg2);

        // When in the default workspace, we should be able to find it without the prefix
        assertNotNull(catalog.getLayerGroupByName("layerGroup2"));
        assertNotNull(catalog.getLayerGroupByName(data.workspaceA.getName() + ":layerGroup2"));
        assertNotNull(catalog.getLayerGroupByName(catalog.getDefaultWorkspace(), "layerGroup2"));
        assertNull(catalog.getLayerGroupByName("cite", "layerGroup2"));

        // Repeat in a non-default workspace
        WorkspaceInfo ws2 = catalog.getFactory().createWorkspace();
        ws2.setName("ws2");
        catalog.add(ws2);
        catalog.setDefaultWorkspace(ws2);

        assertNull(
                catalog.getLayerGroupByName("layerGroup2"),
                "layerGroup2 is not global, should not be found");
        assertNotNull(catalog.getLayerGroupByName(data.workspaceA.getName() + ":layerGroup2"));
        assertNotNull(catalog.getLayerGroupByName(data.workspaceA, "layerGroup2"));
        assertNull(catalog.getLayerGroupByName("cite", "layerGroup2"));
    }

    @Test
    public void testRemoveLayerGroupAndAssociatedDataRules() throws IOException {
        DataAccessRuleDAO dao = this.dataAccessRuleDAO;
        CatalogListener listener = new SecuredResourceNameChangeListener(catalog, dao);
        addLayer();
        CatalogFactory factory = catalog.getFactory();
        LayerGroupInfo lg = factory.createLayerGroup();
        String lgName = "MyFakeWorkspace:layerGroup";
        lg.setName(lgName);
        lg.setWorkspace(data.workspaceA);
        lg.getLayers().add(data.layerFeatureTypeA);
        lg.getStyles().add(data.style1);
        catalog.add(lg);
        String workspaceName = data.workspaceA.getName();
        assertNotNull(catalog.getLayerGroupByName(workspaceName, lg.getName()));

        addLayerAccessRule(workspaceName, lg.getName(), AccessMode.WRITE, "*");
        assertTrue(layerHasSecurityRule(dao, workspaceName, lg.getName()));
        catalog.remove(lg);
        assertNull(catalog.getLayerGroupByName(workspaceName, lg.getName()));
        assertFalse(layerHasSecurityRule(dao, workspaceName, lg.getName()));
        catalog.removeListener(listener);
    }

    @Test
    public void testGetLayerGroupByNameWithColon() {
        addLayer();
        CatalogFactory factory = catalog.getFactory();
        LayerGroupInfo lg = factory.createLayerGroup();

        String lgName = "MyFakeWorkspace:layerGroup";
        lg.setName(lgName);
        lg.setWorkspace(data.workspaceA);
        lg.getLayers().add(data.layerFeatureTypeA);
        lg.getStyles().add(data.style1);
        catalog.add(lg);

        // lg is not global, should not be found at least we specify a prefixed name
        assertNull(
                catalog.getLayerGroupByName(lgName),
                "MyFakeWorkspace:layerGroup is not global, should not be found");

        assertEquals(lg, catalog.getLayerGroupByName(data.workspaceA.getName(), lgName));
        assertEquals(lg, catalog.getLayerGroupByName(data.workspaceA, lgName));
        assertEquals(lg, catalog.getLayerGroupByName(data.workspaceA.getName() + ":" + lgName));
    }

    @Test
    public void testGetLayerGroupByNameWithWorkspace() {
        addLayer();
        assertEquals(data.workspaceA, catalog.getDefaultWorkspace());

        CatalogFactory factory = catalog.getFactory();
        LayerGroupInfo lg1 = factory.createLayerGroup();
        lg1.setName("lg");
        lg1.setWorkspace(data.workspaceA);
        lg1.getLayers().add(data.layerFeatureTypeA);
        lg1.getStyles().add(data.style1);
        catalog.add(lg1);

        WorkspaceInfo ws2 = factory.createWorkspace();
        ws2.setName("ws2");
        catalog.add(ws2);

        NamespaceInfo ns2 = factory.createNamespace();
        // namespace prefix shall match workspace name, until we decide it cannot
        ns2.setPrefix("ns2");
        // ns2.setPrefix(ws2.getName());
        ns2.setURI("http://ns2");
        catalog.add(ns2);

        DataStoreInfo ds2 = factory.createDataStore();
        ds2.setEnabled(true);
        ds2.setName("dsName");
        ds2.setDescription("dsDescription");
        ds2.setWorkspace(ws2);
        catalog.add(ds2);

        FeatureTypeInfo ft2 = factory.createFeatureType();
        ft2.setEnabled(true);
        ft2.setName("ftName");
        ft2.setAbstract("ftAbstract");
        ft2.setDescription("ftDescription");
        ft2.setStore(ds2);
        ft2.setNamespace(ns2);
        catalog.add(ft2);

        StyleInfo s2 = factory.createStyle();
        s2.setName("style1");
        s2.setFilename("styleFilename");
        s2.setWorkspace(ws2);
        catalog.add(s2);

        LayerInfo l2 = factory.createLayer();
        l2.setResource(ft2);
        l2.setEnabled(true);
        l2.setDefaultStyle(s2);
        catalog.add(l2);

        LayerGroupInfo lg2 = catalog.getFactory().createLayerGroup();
        lg2.setName("lg");
        lg2.setWorkspace(ws2);
        lg2.getLayers().add(l2);
        lg2.getStyles().add(s2);
        catalog.add(lg2);

        // lg is not global, but it is in the default workspace, so it should be found if we don't
        // specify the workspace
        assertEquals(lg1, catalog.getLayerGroupByName("lg"));

        assertEquals(lg1, catalog.getLayerGroupByName(data.workspaceA.getName(), "lg"));
        assertEquals(lg1, catalog.getLayerGroupByName(data.workspaceA, "lg"));
        assertEquals(lg1, catalog.getLayerGroupByName(data.workspaceA.getName() + ":lg"));

        assertEquals(lg2, catalog.getLayerGroupByName(ws2, "lg"));
        assertEquals(lg2, catalog.getLayerGroupByName(ws2, "lg"));
        assertEquals(lg2, catalog.getLayerGroupByName(ws2.getName() + ":lg"));
    }

    @Test
    public void testGetLayerGroups() {
        addLayerGroup();
        assertEquals(1, catalog.getLayerGroups().size());
        assertEquals(0, catalog.getLayerGroupsByWorkspace(data.workspaceA.getName()).size());
        assertEquals(0, catalog.getLayerGroupsByWorkspace(data.workspaceA).size());
        assertEquals(0, catalog.getLayerGroupsByWorkspace((WorkspaceInfo) null).size());

        LayerGroupInfo lg2 = catalog.getFactory().createLayerGroup();
        lg2.setWorkspace(catalog.getDefaultWorkspace());
        lg2.setName("layerGroup2");
        lg2.getLayers().add(data.layerFeatureTypeA);
        lg2.getStyles().add(data.style1);
        catalog.add(lg2);

        assertEquals(2, catalog.getLayerGroups().size());
        assertEquals(1, catalog.getLayerGroupsByWorkspace(data.workspaceA.getName()).size());
        assertEquals(1, catalog.getLayerGroupsByWorkspace(data.workspaceA).size());
        assertEquals(1, catalog.getLayerGroupsByWorkspace((WorkspaceInfo) null).size());
    }

    @Test
    public void testLayerGroupTitle() {
        addLayer();
        LayerGroupInfo lg2 = catalog.getFactory().createLayerGroup();
        // lg2.setWorkspace(catalog.getDefaultWorkspace());
        lg2.setName("layerGroup2");
        lg2.setTitle("layerGroup2 title");
        lg2.getLayers().add(data.layerFeatureTypeA);
        lg2.getStyles().add(data.style1);
        catalog.add(lg2);

        assertEquals(1, catalog.getLayerGroups().size());

        lg2 = catalog.getLayerGroupByName("layerGroup2");
        assertEquals("layerGroup2 title", lg2.getTitle());

        lg2.setTitle("another title");
        catalog.save(lg2);

        lg2 = catalog.getLayerGroupByName("layerGroup2");
        assertEquals("another title", lg2.getTitle());
    }

    @Test
    public void testLayerGroupAbstract() {
        addLayer();
        LayerGroupInfo lg2 = catalog.getFactory().createLayerGroup();
        // lg2.setWorkspace(catalog.getDefaultWorkspace());
        lg2.setName("layerGroup2");
        lg2.setAbstract("layerGroup2 abstract");
        lg2.getLayers().add(data.layerFeatureTypeA);
        lg2.getStyles().add(data.style1);
        catalog.add(lg2);

        assertEquals(1, catalog.getLayerGroups().size());

        lg2 = catalog.getLayerGroupByName("layerGroup2");
        assertEquals("layerGroup2 abstract", lg2.getAbstract());

        lg2.setAbstract("another abstract");
        catalog.save(lg2);

        lg2 = catalog.getLayerGroupByName("layerGroup2");
        assertEquals("another abstract", lg2.getAbstract());
    }

    @Test
    public void testLayerGroupType() {
        addLayer();
        LayerGroupInfo lg2 = catalog.getFactory().createLayerGroup();
        lg2.setWorkspace(null);
        lg2.setName("layerGroup2");
        lg2.setMode(LayerGroupInfo.Mode.NAMED);
        lg2.getLayers().add(data.layerFeatureTypeA);
        lg2.getStyles().add(data.style1);
        catalog.add(lg2);

        assertEquals(1, catalog.getLayerGroups().size());

        lg2 = catalog.getLayerGroupByName("layerGroup2");
        assertEquals(LayerGroupInfo.Mode.NAMED, lg2.getMode());

        lg2.setMode(LayerGroupInfo.Mode.SINGLE);
        catalog.save(lg2);

        lg2 = catalog.getLayerGroupByName("layerGroup2");
        assertEquals(LayerGroupInfo.Mode.SINGLE, lg2.getMode());
    }

    @Test
    public void testLayerGroupRootLayer() {
        addLayer();
        LayerGroupInfo lg2 = catalog.getFactory().createLayerGroup();
        lg2.setWorkspace(null);
        lg2.setName("layerGroup2");
        lg2.getLayers().add(data.layerFeatureTypeA);
        lg2.getStyles().add(data.style1);
        lg2.setRootLayer(data.layerFeatureTypeA);

        lg2.setMode(LayerGroupInfo.Mode.SINGLE);
        try {
            catalog.add(lg2);
            fail("only EO layer groups can have a root layer");
        } catch (IllegalArgumentException e) {
            assertTrue(true);
        }

        lg2.setMode(LayerGroupInfo.Mode.NAMED);
        try {
            catalog.add(lg2);
            fail("only EO layer groups can have a root layer");
        } catch (IllegalArgumentException e) {
            assertTrue(true);
        }

        lg2.setMode(LayerGroupInfo.Mode.CONTAINER);
        try {
            catalog.add(lg2);
            fail("only EO layer groups can have a root layer");
        } catch (IllegalArgumentException e) {
            assertTrue(true);
        }

        lg2.setMode(LayerGroupInfo.Mode.EO);
        lg2.setRootLayer(null);
        try {
            catalog.add(lg2);
            fail("EO layer groups must have a root layer");
        } catch (IllegalArgumentException e) {
            assertTrue(true);
        }

        lg2.setRootLayer(data.layerFeatureTypeA);
        try {
            catalog.add(lg2);
            fail("EO layer groups must have a root layer style");
        } catch (IllegalArgumentException e) {
            assertTrue(true);
        }

        lg2.setRootLayerStyle(data.style1);

        catalog.add(lg2);
        assertEquals(1, catalog.getLayerGroups().size());

        lg2 = catalog.getLayerGroupByName("layerGroup2");
        assertEquals(LayerGroupInfo.Mode.EO, lg2.getMode());
        assertEquals(data.layerFeatureTypeA, lg2.getRootLayer());
        assertEquals(data.style1, lg2.getRootLayerStyle());
    }

    @Test
    public void testLayerGroupNullLayerReferences() {
        addLayer();
        LayerGroupInfo lg = catalog.getFactory().createLayerGroup();
        lg.setWorkspace(null);
        lg.setName("layerGroup2");
        lg.getLayers().add(null);
        lg.getStyles().add(null);
        lg.getLayers().add(data.layerFeatureTypeA);
        lg.getStyles().add(data.style1);
        lg.getLayers().add(null);
        lg.getStyles().add(null);

        catalog.add(lg);
        LayerGroupInfo resolved = catalog.getLayerGroupByName("layerGroup2");
        assertEquals(1, resolved.layers().size());
        assertEquals(1, resolved.styles().size());
        assertEquals(data.style1, resolved.styles().get(0));
    }

    @Test
    public void testLayerGroupRenderingLayers() {
        addDataStore();
        addNamespace();
        FeatureTypeInfo ft1, ft2, ft3;
        catalog.add(ft1 = newFeatureType("ft1", data.dataStoreA));
        catalog.add(ft2 = newFeatureType("ft2", data.dataStoreA));
        catalog.add(ft3 = newFeatureType("ft3", data.dataStoreA));

        StyleInfo s1, s2, s3;
        catalog.add(s1 = newStyle("s1", "s1Filename"));
        catalog.add(s2 = newStyle("s2", "s2Filename"));
        catalog.add(s3 = newStyle("s3", "s3Filename"));

        LayerInfo l1, l2, l3;
        catalog.add(l1 = newLayer(ft1, s1));
        catalog.add(l2 = newLayer(ft2, s2));
        catalog.add(l3 = newLayer(ft3, s3));

        LayerGroupInfo lg2 = catalog.getFactory().createLayerGroup();
        lg2.setWorkspace(catalog.getDefaultWorkspace());
        lg2.setName("layerGroup2");
        lg2.getLayers().add(l1);
        lg2.getLayers().add(l2);
        lg2.getLayers().add(l3);
        lg2.getStyles().add(s1);
        lg2.getStyles().add(s2);
        lg2.getStyles().add(s3);

        lg2.setRootLayer(data.layerFeatureTypeA);
        lg2.setRootLayerStyle(data.style1);

        lg2.setMode(LayerGroupInfo.Mode.SINGLE);
        assertEquals(lg2.getLayers(), lg2.layers());
        assertEquals(lg2.getStyles(), lg2.styles());

        lg2.setMode(LayerGroupInfo.Mode.OPAQUE_CONTAINER);
        assertEquals(lg2.getLayers(), lg2.layers());
        assertEquals(lg2.getStyles(), lg2.styles());

        lg2.setMode(LayerGroupInfo.Mode.NAMED);
        assertEquals(lg2.getLayers(), lg2.layers());
        assertEquals(lg2.getStyles(), lg2.styles());

        lg2.setMode(LayerGroupInfo.Mode.CONTAINER);
        try {
            assertEquals(lg2.getLayers(), lg2.layers());
            fail("Layer group of Type Container can not be rendered");
        } catch (UnsupportedOperationException e) {
            assertTrue(true);
        }
        try {
            assertEquals(lg2.getStyles(), lg2.styles());
            fail("Layer group of Type Container can not be rendered");
        } catch (UnsupportedOperationException e) {
            assertTrue(true);
        }

        lg2.setMode(LayerGroupInfo.Mode.EO);
        assertEquals(1, lg2.layers().size());
        assertEquals(1, lg2.styles().size());
        assertEquals(data.layerFeatureTypeA, lg2.layers().iterator().next());
        assertEquals(data.style1, lg2.styles().iterator().next());
    }

    @Test
    public void testRemoveLayerGroupInLayerGroup() throws Exception {
        addLayerGroup();

        LayerGroupInfo lg2 = catalog.getFactory().createLayerGroup();

        lg2.setName("layerGroup2");
        lg2.getLayers().add(data.layerGroup1);
        lg2.getStyles().add(data.style1);
        catalog.add(lg2);

        try {
            catalog.remove(data.layerGroup1);
            fail("should have failed because lg is in another lg");
        } catch (IllegalArgumentException expected) {
        }

        // removing the containing layer first should work
        catalog.remove(lg2);
        catalog.remove(data.layerGroup1);
    }

    protected static class TestListener implements CatalogListener {
        public List<CatalogAddEvent> added = new CopyOnWriteArrayList<>();
        public List<CatalogModifyEvent> modified = new CopyOnWriteArrayList<>();
        public List<CatalogPostModifyEvent> postModified = new CopyOnWriteArrayList<>();
        public List<CatalogRemoveEvent> removed = new CopyOnWriteArrayList<>();

        public void handleAddEvent(CatalogAddEvent event) {
            added.add(event);
        }

        public void handleModifyEvent(CatalogModifyEvent event) {
            modified.add(event);
        }

        public void handlePostModifyEvent(CatalogPostModifyEvent event) {
            postModified.add(event);
        }

        public void handleRemoveEvent(CatalogRemoveEvent event) {
            removed.add(event);
        }

        public void reloaded() {}
    }

    protected static class ExceptionThrowingListener implements CatalogListener {

        public boolean throwCatalogException;

        public void handleAddEvent(CatalogAddEvent event) throws CatalogException {
            if (throwCatalogException) {
                throw new CatalogException("expected, testing Catalog's CatalogException handling");
            } else {
                throw new RuntimeException("expected, testing Catalog's RuntimeException handling");
            }
        }

        public void handleModifyEvent(CatalogModifyEvent event) throws CatalogException {}

        public void handlePostModifyEvent(CatalogPostModifyEvent event) throws CatalogException {}

        public void handleRemoveEvent(CatalogRemoveEvent event) throws CatalogException {}

        public void reloaded() {}
    }

    class LayerAddRunner extends RunnerBase {

        private int idx;

        protected LayerAddRunner(CountDownLatch ready, CountDownLatch done, int idx) {
            super(ready, done);
            this.idx = idx;
        }

        protected void runInternal() throws Exception {
            CatalogFactory factory = catalog.getFactory();
            for (int i = 0; i < GET_LAYER_BY_ID_WITH_CONCURRENT_ADD_TEST_COUNT; i++) {
                // GR: Adding a new feature type info too, we can't really add multiple layers per
                // feature type yet. Setting the name of the layer changes the name of the resource,
                // then all previous layers for that resource get screwed
                String name = "LAYER-" + i + "-" + idx;
                FeatureTypeInfo resource = factory.createFeatureType();
                resource.setName(name);
                resource.setNamespace(data.namespaceA);
                resource.setStore(data.dataStoreA);
                catalog.add(resource);

                LayerInfo layer = factory.createLayer();
                layer.setResource(resource);
                layer.setName(name);
                catalog.add(layer);
            }
        }
    }
    ;

    @Test
    public void testGet() {
        addDataStore();
        addNamespace();

        FeatureTypeInfo ft1 = newFeatureType("ft1", data.dataStoreA);
        ft1.getKeywords().add(new Keyword("kw1_ft1"));
        ft1.getKeywords().add(new Keyword("kw2_ft1"));
        ft1.getKeywords().add(new Keyword("repeatedKw"));

        FeatureTypeInfo ft2 = newFeatureType("ft2", data.dataStoreA);
        ft2.getKeywords().add(new Keyword("kw1_ft2"));
        ft2.getKeywords().add(new Keyword("kw2_ft2"));
        ft2.getKeywords().add(new Keyword("repeatedKw"));

        catalog.add(ft1);
        catalog.add(ft2);

        StyleInfo s1, s2, s3;
        catalog.add(s1 = newStyle("s1", "s1Filename"));
        catalog.add(s2 = newStyle("s2", "s2Filename"));
        catalog.add(s3 = newStyle("s3", "s3Filename"));

        LayerInfo l1 = newLayer(ft1, s1, s2, s3);
        LayerInfo l2 = newLayer(ft2, s2, s1, s3);
        catalog.add(l1);
        catalog.add(l2);

        Filter filter = acceptAll();
        try {
            catalog.get(null, filter);
            fail("Expected precondition validation exception");
        } catch (RuntimeException nullCheck) {
            assertTrue(true);
        }
        try {
            catalog.get(FeatureTypeInfo.class, null);
            fail("Expected precondition validation exception");
        } catch (RuntimeException nullCheck) {
            assertTrue(true);
        }

        try {
            catalog.get(FeatureTypeInfo.class, filter);
            fail("Expected IAE on multiple results");
        } catch (IllegalArgumentException multipleResults) {
            assertTrue(true);
        }

        filter = equal("id", ft1.getId());
        FeatureTypeInfo featureTypeInfo = catalog.get(FeatureTypeInfo.class, filter);
        assertEquals(ft1.getId(), featureTypeInfo.getId());
        assertSame(catalog, featureTypeInfo.getCatalog());

        filter = equal("name", ft2.getName());
        assertEquals(ft2.getName(), catalog.get(ResourceInfo.class, filter).getName());

        filter = equal("keywords[1].value", ft1.getKeywords().get(0).getValue());
        assertEquals(ft1.getName(), catalog.get(ResourceInfo.class, filter).getName());

        filter = equal("keywords[2]", ft2.getKeywords().get(1));
        assertEquals(ft2.getName(), catalog.get(FeatureTypeInfo.class, filter).getName());

        filter = equal("keywords[3].value", "repeatedKw");
        try {
            catalog.get(FeatureTypeInfo.class, filter).getName();
            fail("Expected IAE on multiple results");
        } catch (IllegalArgumentException multipleResults) {
            assertTrue(true);
        }

        filter = equal("defaultStyle.filename", "s1Filename");
        assertEquals(l1.getId(), catalog.get(LayerInfo.class, filter).getId());

        filter = equal("defaultStyle.name", s2.getName());
        assertEquals(l2.getId(), catalog.get(LayerInfo.class, filter).getId());
        // Waiting for fix of MultiCompareFilterImpl.evaluate for Sets
        // filter = equal("styles", l2.getStyles(), MatchAction.ALL);
        // assertEquals(l2.getId(), catalog.get(LayerInfo.class, filter).getId());

        filter = equal("styles.id", s2.getId(), MatchAction.ONE);
        assertEquals(l1.getId(), catalog.get(LayerInfo.class, filter).getId());

        filter = equal("styles.id", s3.getId(), MatchAction.ANY); // s3 is shared by l1 and l2
        try {
            catalog.get(LayerInfo.class, filter);
            fail("Expected IAE on multiple results");
        } catch (IllegalArgumentException multipleResults) {
            assertTrue(true);
        }
    }

    @Test
    public void testListPredicate() {
        addDataStore();
        addNamespace();

        FeatureTypeInfo ft1, ft2, ft3;

        catalog.add(ft1 = newFeatureType("ft1", data.dataStoreA));
        catalog.add(ft2 = newFeatureType("ft2", data.dataStoreA));
        catalog.add(ft3 = newFeatureType("ft3", data.dataStoreA));
        ft1 = catalog.getFeatureType(ft1.getId());
        ft2 = catalog.getFeatureType(ft2.getId());
        ft3 = catalog.getFeatureType(ft3.getId());

        Filter filter = acceptAll();
        Set<? extends CatalogInfo> expected;
        Set<? extends CatalogInfo> actual;

        expected = Sets.newHashSet(ft1, ft2, ft3);
        actual = Sets.newHashSet(catalog.list(FeatureTypeInfo.class, filter));
        assertEquals(3, actual.size());
        assertEquals(expected, actual);

        filter = contains("name", "t");
        actual = Sets.newHashSet(catalog.list(FeatureTypeInfo.class, filter));
        assertTrue(expected.equals(actual));
        assertEquals(expected, actual);

        filter = or(contains("name", "t2"), contains("name", "t1"));
        expected = Sets.newHashSet(ft1, ft2);
        actual = Sets.newHashSet(catalog.list(FeatureTypeInfo.class, filter));
        assertEquals(expected, actual);

        StyleInfo s1, s2, s3, s4, s5, s6;
        catalog.add(s1 = newStyle("s1", "s1Filename"));
        catalog.add(s2 = newStyle("s2", "s2Filename"));
        catalog.add(s3 = newStyle("s3", "s3Filename"));
        catalog.add(s4 = newStyle("s4", "s4Filename"));
        catalog.add(s5 = newStyle("s5", "s5Filename"));
        catalog.add(s6 = newStyle("s6", "s6Filename"));

        @SuppressWarnings("unused")
        LayerInfo l1, l2, l3;
        catalog.add(l1 = newLayer(ft1, s1));
        catalog.add(l2 = newLayer(ft2, s2, s3, s4));
        catalog.add(l3 = newLayer(ft3, s3, s5, s6));

        filter = contains("styles.name", "s6");
        expected = Sets.newHashSet(l3);
        actual = Sets.newHashSet(catalog.list(LayerInfo.class, filter));
        assertEquals(expected, actual);

        filter = equal("defaultStyle.name", "s1");
        expected = Sets.newHashSet(l1);
        actual = Sets.newHashSet(catalog.list(LayerInfo.class, filter));
        assertEquals(expected, actual);

        filter = or(contains("styles.name", "s6"), equal("defaultStyle.name", "s1"));
        expected = Sets.newHashSet(l1, l3);
        actual = Sets.newHashSet(catalog.list(LayerInfo.class, filter));
        assertEquals(expected, actual);

        filter = acceptAll();
        ArrayList<LayerInfo> naturalOrder =
                Lists.newArrayList(catalog.list(LayerInfo.class, filter));
        assertEquals(3, naturalOrder.size());

        int offset = 0, limit = 2;
        assertEquals(
                naturalOrder.subList(0, 2),
                Lists.newArrayList(catalog.list(LayerInfo.class, filter, offset, limit, null)));

        offset = 1;
        assertEquals(
                naturalOrder.subList(1, 3),
                Lists.newArrayList(catalog.list(LayerInfo.class, filter, offset, limit, null)));

        limit = 1;
        assertEquals(
                naturalOrder.subList(1, 2),
                Lists.newArrayList(catalog.list(LayerInfo.class, filter, offset, limit, null)));
    }

    /**
     * This tests more advanced filters: multi-valued filters, opposite equations, field equations
     */
    @Test
    public void testListPredicateExtended() {
        addDataStore();
        addNamespace();

        final FilterFactory factory = CommonFactoryFinder.getFilterFactory();

        FeatureTypeInfo ft1, ft2, ft3;

        catalog.add(ft1 = newFeatureType("ft1", data.dataStoreA));
        catalog.add(ft2 = newFeatureType("ft2", data.dataStoreA));
        catalog.add(ft3 = newFeatureType("ft3", data.dataStoreA));
        ft1 = catalog.getFeatureType(ft1.getId());
        ft2 = catalog.getFeatureType(ft2.getId());
        ft3 = catalog.getFeatureType(ft3.getId());
        ft1.getKeywords().add(new Keyword("keyword1"));
        ft1.getKeywords().add(new Keyword("keyword2"));
        ft1.getKeywords().add(new Keyword("ft1"));
        ft1.setDescription("ft1 description");
        catalog.save(ft1);
        ft2.getKeywords().add(new Keyword("keyword1"));
        ft2.getKeywords().add(new Keyword("keyword1"));
        ft2.setDescription("ft2");
        catalog.save(ft2);
        ft3.getKeywords().add(new Keyword("ft3"));
        ft3.getKeywords().add(new Keyword("ft3"));
        ft3.setDescription("FT3");
        catalog.save(ft3);

        Filter filter = acceptAll();
        Set<? extends CatalogInfo> expected;
        Set<? extends CatalogInfo> actual;

        // opposite equality
        filter = factory.equal(factory.literal(ft1.getId()), factory.property("id"), true);
        expected = Sets.newHashSet(ft1);
        actual = Sets.newHashSet(catalog.list(ResourceInfo.class, filter));
        assertEquals(expected, actual);

        // match case
        filter = factory.equal(factory.literal("FT1"), factory.property("name"), false);
        expected = Sets.newHashSet(ft1);
        actual = Sets.newHashSet(catalog.list(ResourceInfo.class, filter));
        assertEquals(expected, actual);

        // equality of fields
        filter = factory.equal(factory.property("name"), factory.property("description"), true);
        expected = Sets.newHashSet(ft2);
        actual = Sets.newHashSet(catalog.list(ResourceInfo.class, filter));
        assertEquals(expected, actual);

        // match case
        filter = factory.equal(factory.property("name"), factory.property("description"), false);
        expected = Sets.newHashSet(ft2, ft3);
        actual = Sets.newHashSet(catalog.list(ResourceInfo.class, filter));
        assertEquals(expected, actual);

        // match action
        filter =
                factory.equal(
                        factory.literal(new Keyword("keyword1")),
                        factory.property("keywords"),
                        true,
                        MatchAction.ANY);
        expected = Sets.newHashSet(ft1, ft2);
        actual = Sets.newHashSet(catalog.list(FeatureTypeInfo.class, filter));
        assertEquals(expected, actual);

        filter =
                factory.equal(
                        factory.literal(new Keyword("keyword1")),
                        factory.property("keywords"),
                        true,
                        MatchAction.ALL);
        expected = Sets.newHashSet(ft2);
        actual = Sets.newHashSet(catalog.list(FeatureTypeInfo.class, filter));
        assertEquals(expected, actual);

        filter =
                factory.equal(
                        factory.literal(new Keyword("keyword1")),
                        factory.property("keywords"),
                        true,
                        MatchAction.ONE);
        expected = Sets.newHashSet(ft1);
        actual = Sets.newHashSet(catalog.list(FeatureTypeInfo.class, filter));
        assertEquals(expected, actual);

        // match action - like
        filter =
                factory.like(
                        factory.property("keywords"),
                        "key*d1",
                        "*",
                        "?",
                        "\\",
                        true,
                        MatchAction.ANY);
        expected = Sets.newHashSet(ft1, ft2);
        actual = Sets.newHashSet(catalog.list(FeatureTypeInfo.class, filter));
        assertEquals(expected, actual);

        filter =
                factory.like(
                        factory.property("keywords"),
                        "key*d1",
                        "*",
                        "?",
                        "\\",
                        true,
                        MatchAction.ALL);
        expected = Sets.newHashSet(ft2);
        actual = Sets.newHashSet(catalog.list(FeatureTypeInfo.class, filter));
        assertEquals(expected, actual);

        filter =
                factory.like(
                        factory.property("keywords"),
                        "key*d1",
                        "*",
                        "?",
                        "\\",
                        true,
                        MatchAction.ONE);
        expected = Sets.newHashSet(ft1);
        actual = Sets.newHashSet(catalog.list(FeatureTypeInfo.class, filter));
        assertEquals(expected, actual);

        // multivalued literals
        List<Object> values = new ArrayList<>();
        values.add("ft1");
        values.add("ft2");
        filter =
                factory.equal(
                        factory.literal(values), factory.property("name"), true, MatchAction.ANY);
        expected = Sets.newHashSet(ft1, ft2);
        actual = Sets.newHashSet(catalog.list(FeatureTypeInfo.class, filter));
        assertEquals(expected, actual);

        values = new ArrayList<>();
        values.add("ft1");
        values.add("ft1");
        filter =
                factory.equal(
                        factory.literal(values), factory.property("name"), true, MatchAction.ALL);
        expected = Sets.newHashSet(ft1);
        actual = Sets.newHashSet(catalog.list(FeatureTypeInfo.class, filter));
        assertEquals(expected, actual);

        values = new ArrayList<>();
        values.add("ft1");
        values.add("ft2");
        filter =
                factory.equal(
                        factory.literal(values), factory.property("name"), true, MatchAction.ALL);
        expected = Sets.newHashSet();
        actual = Sets.newHashSet(catalog.list(FeatureTypeInfo.class, filter));
        assertEquals(expected, actual);

        values = new ArrayList<>();
        values.add("ft1");
        values.add("ft1");
        values.add("ft2");
        filter =
                factory.equal(
                        factory.literal(values), factory.property("name"), true, MatchAction.ONE);
        expected = Sets.newHashSet(ft2);
        actual = Sets.newHashSet(catalog.list(FeatureTypeInfo.class, filter));
        assertEquals(expected, actual);

        // multivalued literals with multivalued fields

        values = new ArrayList<>();
        values.add(new Keyword("keyword1"));
        values.add(new Keyword("keyword2"));
        filter =
                factory.equal(
                        factory.literal(values),
                        factory.property("keywords"),
                        true,
                        MatchAction.ANY);
        expected = Sets.newHashSet(ft1, ft2);
        actual = Sets.newHashSet(catalog.list(FeatureTypeInfo.class, filter));
        assertEquals(expected, actual);

        values = new ArrayList<>();
        values.add(new Keyword("keyword1"));
        values.add(new Keyword("keyword1"));
        filter =
                factory.equal(
                        factory.literal(values),
                        factory.property("keywords"),
                        true,
                        MatchAction.ALL);
        expected = Sets.newHashSet(ft2);
        actual = Sets.newHashSet(catalog.list(FeatureTypeInfo.class, filter));
        assertEquals(expected, actual);

        values = new ArrayList<>();
        values.add(new Keyword("keyword1"));
        values.add(new Keyword("blah"));
        filter =
                factory.equal(
                        factory.literal(values),
                        factory.property("keywords"),
                        true,
                        MatchAction.ONE);
        expected = Sets.newHashSet(ft1);
        actual = Sets.newHashSet(catalog.list(FeatureTypeInfo.class, filter));
        assertEquals(expected, actual);
    }

    @Test
    public void testOrderBy() {
        addDataStore();
        addNamespace();

        FeatureTypeInfo ft1 = newFeatureType("ft1", data.dataStoreA);
        FeatureTypeInfo ft2 = newFeatureType("ft2", data.dataStoreA);
        FeatureTypeInfo ft3 = newFeatureType("ft3", data.dataStoreA);

        ft2.getKeywords().add(new Keyword("keyword1"));
        ft2.getKeywords().add(new Keyword("keyword2"));

        catalog.add(ft1);
        catalog.add(ft2);
        catalog.add(ft3);

        StyleInfo s1, s2, s3, s4, s5, s6;
        catalog.add(s1 = newStyle("s1", "s1Filename"));
        catalog.add(s2 = newStyle("s2", "s2Filename"));
        catalog.add(s3 = newStyle("s3", "s3Filename"));
        catalog.add(s4 = newStyle("s4", "s4Filename"));
        catalog.add(s5 = newStyle("s5", "s5Filename"));
        catalog.add(s6 = newStyle("s6", "s6Filename"));

        LayerInfo l1 = newLayer(ft1, s1);
        LayerInfo l2 = newLayer(ft2, s1, s3, s4);
        LayerInfo l3 = newLayer(ft3, s2, s5, s6);
        catalog.add(l1);
        catalog.add(l2);
        catalog.add(l3);

        assertEquals(3, catalog.getLayers().size());

        Filter filter;
        SortBy sortOrder;
        List<LayerInfo> expected;

        filter = acceptAll();
        sortOrder = asc("resource.name");
        expected = Lists.newArrayList(l1, l2, l3);
        assertEquals(3, catalog.count(LayerInfo.class, filter));

        testOrderBy(LayerInfo.class, filter, null, null, sortOrder, expected);

        sortOrder = desc("resource.name");
        expected = Lists.newArrayList(l3, l2, l1);

        testOrderBy(LayerInfo.class, filter, null, null, sortOrder, expected);

        sortOrder = asc("defaultStyle.name");
        expected = Lists.newArrayList(l1, l2, l3);
        testOrderBy(LayerInfo.class, filter, null, null, sortOrder, expected);
        sortOrder = desc("defaultStyle.name");
        expected = Lists.newArrayList(l3, l2, l1);

        testOrderBy(LayerInfo.class, filter, null, null, sortOrder, expected);

        expected = Lists.newArrayList(l2, l1);
        testOrderBy(LayerInfo.class, filter, 1, null, sortOrder, expected);

        expected = Lists.newArrayList(l2);
        testOrderBy(LayerInfo.class, filter, 1, 1, sortOrder, expected);
        sortOrder = asc("defaultStyle.name");
        expected = Lists.newArrayList(l2, l3);
        testOrderBy(LayerInfo.class, filter, 1, 10, sortOrder, expected);

        filter = equal("styles.name", s3.getName());
        expected = Lists.newArrayList(l2);
        testOrderBy(LayerInfo.class, filter, 0, 10, sortOrder, expected);
        assertEquals(1, catalog.count(LayerInfo.class, filter));
    }

    private <T extends CatalogInfo> void testOrderBy(
            Class<T> clazz,
            Filter filter,
            Integer offset,
            Integer limit,
            SortBy sortOrder,
            List<T> expected) {

        CatalogPropertyAccessor pe = new CatalogPropertyAccessor();

        List<Object> props = new ArrayList<Object>();
        List<Object> actual = new ArrayList<Object>();
        String sortProperty = sortOrder.getPropertyName().getPropertyName();
        for (T info : expected) {
            Object pval = pe.getProperty(info, sortProperty);
            props.add(pval);
        }

        CloseableIterator<T> it = catalog.list(clazz, filter, offset, limit, sortOrder);
        try {
            while (it.hasNext()) {
                Object property = pe.getProperty(it.next(), sortProperty);
                actual.add(property);
            }
        } finally {
            it.close();
        }

        assertEquals(props, actual);
    }

    @Test
    public void testFullTextSearch() {
        // test layer title search
        data.featureTypeA.setTitle("Global .5 deg Air Temperature [C]");
        data.coverageA.setTitle("Global .5 deg Dewpoint Depression [C]");

        data.featureTypeA.setDescription("FeatureType description");
        data.featureTypeA.setAbstract("GeoServer OpenSource GIS");
        data.coverageA.setDescription("Coverage description");
        data.coverageA.setAbstract("GeoServer uses GeoTools");

        data.layerFeatureTypeA.setResource(data.featureTypeA);

        addLayer();
        catalog.add(data.coverageStoreA);
        catalog.add(data.coverageA);

        LayerInfo l2 = newLayer(data.coverageA, data.style1);
        catalog.add(l2);

        Filter filter = Predicates.fullTextSearch("Description");
        assertEquals(
                newHashSet(data.featureTypeA, data.coverageA),
                asSet(catalog.list(ResourceInfo.class, filter)));
        assertEquals(2, catalog.count(ResourceInfo.class, filter));
        assertEquals(1, catalog.count(CoverageInfo.class, filter));

        assertEquals(
                newHashSet(data.featureTypeA), asSet(catalog.list(FeatureTypeInfo.class, filter)));
        assertEquals(newHashSet(data.coverageA), asSet(catalog.list(CoverageInfo.class, filter)));

        assertEquals(
                newHashSet(data.layerFeatureTypeA, l2),
                asSet(catalog.list(LayerInfo.class, filter)));

        filter = Predicates.fullTextSearch("opensource");
        assertEquals(
                newHashSet(data.layerFeatureTypeA), asSet(catalog.list(LayerInfo.class, filter)));

        filter = Predicates.fullTextSearch("geotools");
        assertEquals(newHashSet(l2), asSet(catalog.list(LayerInfo.class, filter)));

        filter = Predicates.fullTextSearch("Global");
        assertEquals(
                newHashSet(data.layerFeatureTypeA, l2),
                asSet(catalog.list(LayerInfo.class, filter)));

        filter = Predicates.fullTextSearch("Temperature");
        assertEquals(
                newHashSet(data.layerFeatureTypeA), asSet(catalog.list(LayerInfo.class, filter)));

        filter = Predicates.fullTextSearch("Depression");
        assertEquals(newHashSet(l2), asSet(catalog.list(LayerInfo.class, filter)));
    }

    @Test
    public void testFullTextSearchLayerGroupTitle() {
        addLayer();
        // geos-6882
        data.layerGroup1.setTitle("LayerGroup title");
        catalog.add(data.layerGroup1);

        // test layer group title and abstract search
        Filter filter = Predicates.fullTextSearch("title");
        assertEquals(
                newHashSet(data.layerGroup1), asSet(catalog.list(LayerGroupInfo.class, filter)));
    }

    @Test
    public void testFullTextSearchLayerGroupName() {
        addLayer();
        // geos-6882
        catalog.add(data.layerGroup1);
        Filter filter = Predicates.fullTextSearch("Group");
        assertEquals(
                newHashSet(data.layerGroup1), asSet(catalog.list(LayerGroupInfo.class, filter)));
    }

    @Test
    public void testFullTextSearchLayerGroupAbstract() {
        addLayer();
        data.layerGroup1.setAbstract("GeoServer OpenSource GIS");
        catalog.add(data.layerGroup1);
        Filter filter = Predicates.fullTextSearch("geoserver");
        assertEquals(
                newHashSet(data.layerGroup1), asSet(catalog.list(LayerGroupInfo.class, filter)));
    }

    @Test
    public void testFullTextSearchKeywords() {
        data.featureTypeA.getKeywords().add(new Keyword("air_temp"));
        data.featureTypeA.getKeywords().add(new Keyword("temperatureAir"));
        data.coverageA.getKeywords().add(new Keyword("dwpt_dprs"));
        data.coverageA.getKeywords().add(new Keyword("temperatureDewpointDepression"));

        data.layerFeatureTypeA.setResource(data.featureTypeA);
        addLayer();
        catalog.add(data.coverageStoreA);
        catalog.add(data.coverageA);
        LayerInfo l2 = newLayer(data.coverageA, data.style1);
        catalog.add(l2);

        Filter filter = Predicates.fullTextSearch("temperature");
        assertEquals(
                newHashSet(data.layerFeatureTypeA, l2),
                asSet(catalog.list(LayerInfo.class, filter)));
        assertEquals(
                newHashSet(data.featureTypeA, data.coverageA),
                asSet(catalog.list(ResourceInfo.class, filter)));
        assertEquals(
                newHashSet(data.featureTypeA), asSet(catalog.list(FeatureTypeInfo.class, filter)));
        assertEquals(newHashSet(data.coverageA), asSet(catalog.list(CoverageInfo.class, filter)));

        filter = Predicates.fullTextSearch("air");
        assertEquals(
                newHashSet(data.layerFeatureTypeA), asSet(catalog.list(LayerInfo.class, filter)));
        assertEquals(
                newHashSet(data.featureTypeA), asSet(catalog.list(ResourceInfo.class, filter)));
        assertEquals(
                newHashSet(data.featureTypeA), asSet(catalog.list(FeatureTypeInfo.class, filter)));
        assertEquals(newHashSet(), asSet(catalog.list(CoverageInfo.class, filter)));

        filter = Predicates.fullTextSearch("dewpoint");
        assertEquals(newHashSet(l2), asSet(catalog.list(LayerInfo.class, filter)));
        assertEquals(newHashSet(data.coverageA), asSet(catalog.list(ResourceInfo.class, filter)));
        assertEquals(newHashSet(), asSet(catalog.list(FeatureTypeInfo.class, filter)));
        assertEquals(newHashSet(data.coverageA), asSet(catalog.list(CoverageInfo.class, filter)));

        filter = Predicates.fullTextSearch("pressure");
        assertEquals(newHashSet(), asSet(catalog.list(LayerInfo.class, filter)));
        assertEquals(newHashSet(), asSet(catalog.list(ResourceInfo.class, filter)));
        assertEquals(newHashSet(), asSet(catalog.list(FeatureTypeInfo.class, filter)));
        assertEquals(newHashSet(), asSet(catalog.list(CoverageInfo.class, filter)));
    }

    @Test
    public void testFullTextSearchAddedKeyword() {
        data.featureTypeA.getKeywords().add(new Keyword("air_temp"));
        data.featureTypeA.getKeywords().add(new Keyword("temperatureAir"));

        data.layerFeatureTypeA.setResource(data.featureTypeA);
        addLayer();

        LayerInfo lproxy = catalog.getLayer(data.layerFeatureTypeA.getId());
        FeatureTypeInfo ftproxy = (FeatureTypeInfo) lproxy.getResource();

        ftproxy.getKeywords().add(new Keyword("newKeyword"));
        catalog.save(ftproxy);

        Filter filter = Predicates.fullTextSearch("newKeyword");
        assertEquals(newHashSet(ftproxy), asSet(catalog.list(FeatureTypeInfo.class, filter)));
        assertEquals(newHashSet(lproxy), asSet(catalog.list(LayerInfo.class, filter)));
    }

    private <T> Set<T> asSet(CloseableIterator<T> list) {
        ImmutableSet<T> set;
        try {
            set = ImmutableSet.copyOf(list);
        } finally {
            list.close();
        }
        return set;
    }

    protected LayerInfo newLayer(
            ResourceInfo resource, StyleInfo defStyle, StyleInfo... extraStyles) {
        LayerInfo l2 = catalog.getFactory().createLayer();
        l2.setResource(resource);
        l2.setDefaultStyle(defStyle);
        if (extraStyles != null) {
            for (StyleInfo es : extraStyles) {
                l2.getStyles().add(es);
            }
        }
        return l2;
    }

    protected StyleInfo newStyle(String name, String fileName) {
        StyleInfo s2 = catalog.getFactory().createStyle();
        s2.setName(name);
        s2.setFilename(fileName);
        return s2;
    }

    protected FeatureTypeInfo newFeatureType(String name, DataStoreInfo ds) {
        FeatureTypeInfo ft2 = catalog.getFactory().createFeatureType();
        ft2.setNamespace(data.namespaceA);
        ft2.setName(name);
        ft2.setStore(ds);
        return ft2;
    }

    @Test
    public void testConcurrentCatalogModification() throws Exception {
        Logger logger = Logging.getLogger(CatalogImpl.class);
        final int tasks = 8;
        ExecutorService executor = Executors.newFixedThreadPool(tasks / 2);
        Level previousLevel = logger.getLevel();
        // clear previous listeners
        new ArrayList<>(catalog.getListeners()).forEach(l -> catalog.removeListener(l));
        try {
            // disable logging for this test, it will stay a while in case of failure otherwise
            logger.setLevel(Level.OFF);
            ExecutorCompletionService<Void> completionService =
                    new ExecutorCompletionService<>(executor);
            for (int i = 0; i < tasks; i++) {
                completionService.submit(
                        () -> {
                            // attach listeners
                            List<TestListener> listeners = new ArrayList<>();
                            for (int j = 0; j < 3; j++) {
                                TestListener tl = new TestListener();
                                listeners.add(tl);
                                catalog.addListener(tl);
                            }

                            // simulate catalog removals, check the events get to destination
                            CatalogInfo catalogInfo = new CoverageInfoImpl(catalog);
                            catalog.fireRemoved(catalogInfo);
                            // make sure each listener actually got the message
                            for (TestListener testListener : listeners) {
                                assertTrue(
                                        testListener.removed.stream()
                                                .anyMatch(
                                                        event -> event.getSource() == catalogInfo),
                                        "Did not find the expected even in the listener");
                            }

                            // clear the listeners
                            listeners.forEach(l -> catalog.removeListener(l));
                        },
                        null);
            }
            for (int i = 0; i < tasks; ++i) {
                completionService.take().get();
            }
        } finally {
            executor.shutdown();
            logger.setLevel(previousLevel);
        }
    }

    @Test
    public void testChangeLayerGroupOrder() {
        addLayerGroup();

        // create second layer
        FeatureTypeInfo ft2 = catalog.getFactory().createFeatureType();
        ft2.setName("ft2Name");
        ft2.setStore(data.dataStoreA);
        ft2.setNamespace(data.namespaceA);
        catalog.add(ft2);
        LayerInfo l2 = catalog.getFactory().createLayer();
        l2.setResource(ft2);
        l2.setDefaultStyle(data.style1);
        catalog.add(l2);

        // add to the group
        LayerGroupInfo group = catalog.getLayerGroupByName(data.layerGroup1.getName());
        group.getLayers().add(l2);
        group.getStyles().add(null);
        catalog.save(group);

        // change the layer group order
        group = catalog.getLayerGroupByName(data.layerGroup1.getName());
        PublishedInfo pi = group.getLayers().remove(1);
        group.getLayers().add(0, pi);
        catalog.save(group);

        // create a new style
        StyleInfo s2 = catalog.getFactory().createStyle();
        s2.setName("s2Name");
        s2.setFilename("s2Filename");
        catalog.add(s2);

        // change the default style of l
        LayerInfo ll = catalog.getLayerByName(data.layerFeatureTypeA.prefixedName());
        ll.setDefaultStyle(catalog.getStyleByName(s2.getName()));
        catalog.save(ll);

        // now check that the facade can be compared to itself
        LayerGroupInfo g1 = catalog.getFacade().getLayerGroupByName(data.layerGroup1.getName());
        LayerGroupInfo g2 = catalog.getFacade().getLayerGroupByName(data.layerGroup1.getName());
        assertTrue(LayerGroupInfo.equals(g1, g2));
    }

    @Test
    public void testIterablesHaveCatalogSet() {
        data.addObjects();
        {
            CloseableIterator<StoreInfo> stores = catalog.list(StoreInfo.class, acceptAll());
            assertTrue(stores.hasNext());
            stores.forEachRemaining(s -> assertSame(catalog, s.getCatalog()));
        }
        {
            CloseableIterator<ResourceInfo> resources =
                    catalog.list(ResourceInfo.class, acceptAll());
            assertTrue(resources.hasNext());
            resources.forEachRemaining(r -> assertSame(catalog, r.getCatalog()));
        }
        {
            CloseableIterator<LayerInfo> layers = catalog.list(LayerInfo.class, acceptAll());
            assertTrue(layers.hasNext());
            layers.forEachRemaining(
                    r -> {
                        assertSame(catalog, r.getResource().getCatalog());
                        assertSame(catalog, r.getResource().getStore().getCatalog());
                    });
        }
        {
            CloseableIterator<LayerGroupInfo> groups =
                    catalog.list(LayerGroupInfo.class, acceptAll());
            assertTrue(groups.hasNext());
            groups.forEachRemaining(
                    g -> {
                        List<PublishedInfo> layers = g.getLayers();
                        layers.forEach(
                                p -> {
                                    if (p instanceof LayerInfo) {
                                        LayerInfo l = (LayerInfo) p;
                                        assertSame(catalog, l.getResource().getCatalog());
                                        assertSame(
                                                catalog, l.getResource().getStore().getCatalog());
                                    }
                                });
                    });
        }
    }

    public @Test void testCountIncludeFilter() {
        data.addObjects();
        Filter filter = acceptAll();
        assertEquals(3, catalog.count(WorkspaceInfo.class, filter));
        assertEquals(3, catalog.count(NamespaceInfo.class, filter));

        assertEquals(6, catalog.count(StoreInfo.class, filter));
        assertEquals(3, catalog.count(DataStoreInfo.class, filter));
        assertEquals(1, catalog.count(CoverageStoreInfo.class, filter));
        assertEquals(1, catalog.count(WMSStoreInfo.class, filter));
        assertEquals(1, catalog.count(WMTSStoreInfo.class, filter));

        assertEquals(4, catalog.count(ResourceInfo.class, filter));
        assertEquals(1, catalog.count(FeatureTypeInfo.class, filter));
        assertEquals(1, catalog.count(CoverageInfo.class, filter));
        assertEquals(1, catalog.count(WMSLayerInfo.class, filter));
        assertEquals(1, catalog.count(WMTSLayerInfo.class, filter));

        assertEquals(2, catalog.count(PublishedInfo.class, filter));
        assertEquals(1, catalog.count(LayerInfo.class, filter));
        assertEquals(1, catalog.count(LayerGroupInfo.class, filter));

        assertEquals(2, catalog.count(StyleInfo.class, filter));
    }

    public @Test void testCountIdFilter() {
        data.addObjects();
        assertEquals(1, catalog.count(WorkspaceInfo.class, equal("id", data.workspaceA.getId())));
        assertEquals(0, catalog.count(NamespaceInfo.class, equal("id", data.workspaceA.getId())));

        assertEquals(1, catalog.count(StoreInfo.class, equal("id", data.dataStoreB.getId())));
        assertEquals(1, catalog.count(DataStoreInfo.class, equal("id", data.dataStoreB.getId())));
        assertEquals(
                0, catalog.count(CoverageStoreInfo.class, equal("id", data.dataStoreB.getId())));
    }
}
