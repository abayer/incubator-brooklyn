/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.brooklyn.camp.brooklyn.spi.creation;

import io.brooklyn.camp.CampPlatform;
import io.brooklyn.camp.brooklyn.BrooklynCampConstants;
import io.brooklyn.camp.spi.Assembly;
import io.brooklyn.camp.spi.AssemblyTemplate;
import io.brooklyn.camp.spi.AssemblyTemplate.Builder;
import io.brooklyn.camp.spi.PlatformComponentTemplate;
import io.brooklyn.camp.spi.collection.ResolvableLink;
import io.brooklyn.camp.spi.instantiate.AssemblyTemplateInstantiator;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.camp.brooklyn.api.AssemblyTemplateSpecInstantiator;
import brooklyn.camp.brooklyn.api.HasBrooklynManagementContext;
import brooklyn.catalog.CatalogItem;
import brooklyn.catalog.internal.BasicBrooklynCatalog.BrooklynLoaderTracker;
import brooklyn.config.BrooklynServerConfig;
import brooklyn.entity.Application;
import brooklyn.entity.Entity;
import brooklyn.entity.basic.BasicApplicationImpl;
import brooklyn.entity.proxying.EntitySpec;
import brooklyn.management.ManagementContext;
import brooklyn.management.classloading.BrooklynClassLoadingContext;
import brooklyn.management.internal.EntityManagementUtils;
import brooklyn.management.internal.EntityManagementUtils.CreationResult;
import brooklyn.util.ResourceUtils;
import brooklyn.util.exceptions.Exceptions;
import brooklyn.util.flags.TypeCoercions;
import brooklyn.util.net.Urls;

import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

public class BrooklynAssemblyTemplateInstantiator implements AssemblyTemplateSpecInstantiator {

    private static final Logger log = LoggerFactory.getLogger(BrooklynAssemblyTemplateInstantiator.class);
    
    public static final String NEVER_UNWRAP_APPS_PROPERTY = "wrappedApp";
    
    @Override
    public Assembly instantiate(AssemblyTemplate template, CampPlatform platform) {
        Application app = create(template, platform);
        CreationResult<Application, Void> start = EntityManagementUtils.start(app);
        log.debug("CAMP created "+app+"; starting in "+start.task());
        return platform.assemblies().get(app.getApplicationId());
    }

    public Application create(AssemblyTemplate template, CampPlatform platform) {
        Application instance = EntityManagementUtils.createUnstarted(getBrooklynManagementContext(platform), template);
        log.debug("CAMP created {}", instance);
        return instance;
    }
    
    private ManagementContext getBrooklynManagementContext(CampPlatform platform) {
        return ((HasBrooklynManagementContext)platform).getBrooklynManagementContext();
    }
    
    @SuppressWarnings("unchecked")
    public EntitySpec<? extends Application> createSpec(AssemblyTemplate template, CampPlatform platform, BrooklynClassLoadingContext loader, boolean autoUnwrapIfPossible) {
        log.debug("CAMP creating application instance for {} ({})", template.getId(), template);

        // AssemblyTemplates created via PDP, _specifying_ then entities to put in

        BrooklynComponentTemplateResolver resolver = BrooklynComponentTemplateResolver.Factory.newInstance(
            loader, buildWrapperAppTemplate(template));
        EntitySpec<? extends Application> app = resolver.resolveSpec();
        
        // first build the children into an empty shell app
        List<EntitySpec<?>> childSpecs = buildTemplateServicesAsSpecs(loader, template, platform);
        for (EntitySpec<?> childSpec : childSpecs) {
            app.child(childSpec);
        }
        
        if (autoUnwrapIfPossible && shouldUnwrap(template, app)) {
            EntitySpec<? extends Application> oldApp = app;
            app = (EntitySpec<? extends Application>) Iterables.getOnlyElement( app.getChildren() );
            
            // if promoted, apply the transformations done to the app
            // (transformations will be done by the resolveSpec call above, but we are collapsing oldApp so transfer to app=newApp)
            EntityManagementUtils.collapseSpec(oldApp, app);
        }
        
        return app;
    }

    private AssemblyTemplate buildWrapperAppTemplate(AssemblyTemplate template) {
        Builder<? extends AssemblyTemplate> builder = AssemblyTemplate.builder();
        builder.type("brooklyn:" + BasicApplicationImpl.class.getName());
        builder.id(template.getId());
        builder.name(template.getName());
        builder.sourceCode(template.getSourceCode());
        for (Entry<String, Object> entry : template.getCustomAttributes().entrySet()) {
            builder.customAttribute(entry.getKey(), entry.getValue());
        }
        builder.instantiator(template.getInstantiator());
        AssemblyTemplate wrapTemplate = builder.build();
        return wrapTemplate;
    }

    protected boolean shouldUnwrap(AssemblyTemplate template, EntitySpec<? extends Application> app) {
        Object leaveWrapped = template.getCustomAttributes().get(NEVER_UNWRAP_APPS_PROPERTY);
        if (leaveWrapped!=null) {
            if (TypeCoercions.coerce(leaveWrapped, Boolean.class))
                return false;
        }
        
        if (app.getChildren().size()!=1) 
            return false;
        
        EntitySpec<?> childSpec = Iterables.getOnlyElement(app.getChildren());
        if (childSpec.getType()==null || !Application.class.isAssignableFrom(childSpec.getType()))
            return false;

        return EntityManagementUtils.hasNoNameOrCustomKeysOrRoot(template, app);
    }

    private List<EntitySpec<?>> buildTemplateServicesAsSpecs(BrooklynClassLoadingContext loader, AssemblyTemplate template, CampPlatform platform) {
        return buildTemplateServicesAsSpecsImpl(loader, template, platform, Sets.<String>newLinkedHashSet());
    }

    private List<EntitySpec<?>> buildTemplateServicesAsSpecsImpl(BrooklynClassLoadingContext loader, AssemblyTemplate template, CampPlatform platform, Set<String> encounteredCatalogTypes) {
        List<EntitySpec<?>> result = Lists.newArrayList();
        
        for (ResolvableLink<PlatformComponentTemplate> ctl: template.getPlatformComponentTemplates().links()) {
            PlatformComponentTemplate appChildComponentTemplate = ctl.resolve();
            BrooklynComponentTemplateResolver entityResolver = BrooklynComponentTemplateResolver.Factory.newInstance(loader, appChildComponentTemplate);
            EntitySpec<?> spec = resolveSpec(entityResolver, encounteredCatalogTypes);
            
            result.add(spec);
        }
        return result;
    }

    private EntitySpec<?> resolveSpec(
            BrooklynComponentTemplateResolver entityResolver, 
            Set<String> encounteredCatalogTypes) {
        ManagementContext mgmt = entityResolver.loader.getManagementContext();

        String brooklynType = entityResolver.getBrooklynType();
        CatalogItem<Entity, EntitySpec<?>> item = entityResolver.getCatalogItem();

        boolean firstOccurrence = encounteredCatalogTypes.add(brooklynType);
        boolean recursiveButTryJava = !firstOccurrence;
        
        if (log.isTraceEnabled()) log.trace("Building CAMP template services: type="+brooklynType+"; item="+item+"; loader="+entityResolver.loader+"; encounteredCatalogTypes="+encounteredCatalogTypes);

        EntitySpec<?> spec = null;
        String protocol = Urls.getProtocol(brooklynType);
        if (protocol != null) {
            if (BrooklynCampConstants.YAML_URL_PROTOCOL_WHITELIST.contains(protocol)) {
                spec = tryResolveYamlURLReferenceSpec(brooklynType, entityResolver.loader, encounteredCatalogTypes);
                if (spec != null) {
                    entityResolver.populateSpec(spec);
                }
            } else {
                log.warn("The reference " + brooklynType + " looks like an URL but the protocol " + 
                        protocol + " isn't white listed (" + BrooklynCampConstants.YAML_URL_PROTOCOL_WHITELIST + "). " +
                        "Will try to load it as catalog item or java type.");
            }
        }
        
        if (spec == null) {
            if (item == null || item.getJavaType() != null || entityResolver.isJavaTypePrefix()) {
                spec = entityResolver.resolveSpec();
            } else if (recursiveButTryJava) {
                if (entityResolver.tryLoadEntityClass().isAbsent()) {
                    throw new IllegalStateException("Recursive reference to " + brooklynType + " (and cannot be resolved as a Java type)");
                }
                spec = entityResolver.resolveSpec();
            } else {
                spec = resolveCatalogYamlReferenceSpec(mgmt, item, encounteredCatalogTypes);
                entityResolver.populateSpec(spec);
            }
        }

        BrooklynClassLoadingContext newLoader = entityResolver.loader;
        buildChildrenEntitySpecs(newLoader, spec, entityResolver.getChildren(entityResolver.attrs.getAllConfig()), encounteredCatalogTypes);
        return spec;
    }

    private EntitySpec<?> tryResolveYamlURLReferenceSpec(
            String brooklynType, BrooklynClassLoadingContext itemLoader, 
            Set<String> encounteredCatalogTypes) {
        ManagementContext mgmt = itemLoader.getManagementContext();
        Reader yaml;
        try {
            ResourceUtils ru = ResourceUtils.create(this);
            yaml = new InputStreamReader(ru.getResourceFromUrl(brooklynType), "UTF-8");
        } catch (Exception e) {
            log.warn("AssemblyTemplate type " + brooklynType + " which looks like a URL can't be fetched.", e);
            return null;
        }
        try {
            return resolveYamlSpec(mgmt, encounteredCatalogTypes, yaml, itemLoader);
        } finally {
            try {
                yaml.close();
            } catch (IOException e) {
                throw Exceptions.propagate(e);
            }
        }
    }

    private EntitySpec<?> resolveCatalogYamlReferenceSpec(
            ManagementContext mgmt,
            CatalogItem<Entity, EntitySpec<?>> item,
            Set<String> encounteredCatalogTypes) {
        
        String yaml = item.getPlanYaml();
        Reader input = new StringReader(yaml);
        BrooklynClassLoadingContext itemLoader = item.newClassLoadingContext(mgmt);
        
        return resolveYamlSpec(mgmt, encounteredCatalogTypes, input, itemLoader);
    }

    private EntitySpec<?> resolveYamlSpec(ManagementContext mgmt,
            Set<String> encounteredCatalogTypes, Reader input,
            BrooklynClassLoadingContext itemLoader) {
        CampPlatform platform = BrooklynServerConfig.getCampPlatform(mgmt).get();
        
        AssemblyTemplate at;
        BrooklynLoaderTracker.setLoader(itemLoader);
        try {
            at = platform.pdp().registerDeploymentPlan(input);
        } finally {
            BrooklynLoaderTracker.unsetLoader(itemLoader);
        }

        // In case we want to allow multiple top-level entities in a catalog we need to think
        // about what it would mean to subsequently call buildChildrenEntitySpecs on the list of top-level entities!
        try {
            AssemblyTemplateInstantiator ati = at.getInstantiator().newInstance();
            if (ati instanceof BrooklynAssemblyTemplateInstantiator) {
                List<EntitySpec<?>> specs = ((BrooklynAssemblyTemplateInstantiator)ati).buildTemplateServicesAsSpecsImpl(itemLoader, at, platform, encounteredCatalogTypes);
                if (specs.size() > 1) {
                    throw new UnsupportedOperationException("Only supporting single service in catalog item currently: got "+specs);
                }
                return specs.get(0);
            } else {
                throw new IllegalStateException("Cannot create application with instantiator: " + ati);
            }
        } catch (Exception e) {
            throw Exceptions.propagate(e);
        }
    }

    protected void buildChildrenEntitySpecs(BrooklynClassLoadingContext loader, EntitySpec<?> parent, List<Map<String, Object>> childConfig, Set<String> encounteredCatalogTypes) {
        if (childConfig != null) {
            for (Map<String, Object> childAttrs : childConfig) {
                BrooklynComponentTemplateResolver entityResolver = BrooklynComponentTemplateResolver.Factory.newInstance(loader, childAttrs);
                EntitySpec<? extends Entity> spec = resolveSpec(entityResolver, encounteredCatalogTypes);
                parent.child(spec);
            }
        }
    }
}
