/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.meecrowave.cdi;

import org.apache.meecrowave.Meecrowave;
import org.apache.meecrowave.logging.jul.Log4j2Logger;
import org.apache.meecrowave.logging.openwebbeans.Log4j2LoggerFactory;
import org.apache.meecrowave.logging.tomcat.Log4j2Log;
import org.apache.meecrowave.openwebbeans.KnowClassesFilter;
import org.apache.meecrowave.openwebbeans.OWBTomcatWebScannerService;
import org.apache.openwebbeans.se.OWBContainer;
import org.apache.openwebbeans.se.OWBInitializer;
import org.apache.webbeans.config.WebBeansContext;
import org.apache.webbeans.spi.ScannerService;
import org.apache.xbean.finder.filter.Filter;

import javax.enterprise.inject.se.SeContainer;
import javax.enterprise.inject.se.SeContainerInitializer;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toSet;

public class MeecrowaveSeContainerInitializer extends OWBInitializer {
    static { // todo: see if we can not do it statically but also means we lazy load OWB which can require some OWB rework
        System.setProperty("java.util.logging.manager",
                System.getProperty("java.util.logging.manager", "org.apache.logging.log4j.jul.LogManager"));
        System.setProperty("openwebbeans.logging.factory",
                System.getProperty("openwebbeans.logging.factory", Log4j2LoggerFactory.class.getName()));
        System.setProperty("org.apache.cxf.Logger",
                System.getProperty("org.apache.cxf.Logger", Log4j2Logger.class.getName()));
        System.setProperty("org.apache.tomcat.Logger",
                System.getProperty("org.apache.tomcat.Logger", Log4j2Log.class.getName()));
    }

    private Meecrowave.Builder builder = new Meecrowave.Builder();

    @Override
    public SeContainerInitializer addProperty(final String s, final Object o) {
        if (Meecrowave.Builder.class.isInstance(o)) {
            builder = Meecrowave.Builder.class.cast(o);
            return this;
        }

        final String setter = "set" + Character.toUpperCase(s.charAt(0)) + s.substring(1);
        final Optional<Method> setterOpt = Stream.of(builder.getClass().getMethods())
                .filter(m -> m.getName().equals(setter) && m.getParameterCount() == 1)
                .findFirst();
        if (!setterOpt.isPresent()) {
            super.addProperty(s, o);
            // todo: log or do we assume delegate will ?
            return this;
        }

        try {
            builder.getClass().getMethod(setter, o.getClass()).invoke(builder, o);
        } catch (final IllegalAccessException | NoSuchMethodException | InvocationTargetException ex) {
            throw new IllegalArgumentException(ex);
        }
        return this;
    }

    @Override
    protected void addCustomServices(final Map<String, Object> services) {
        final Set<String> forced = this.scannerService.configuredClasses().stream().map(Class::getName).collect(toSet());
        services.put(Filter.class.getName(), new KnowClassesFilter() { // override it to make programmatic configuration working OOTB
            @Override
            public boolean accept(final String name) {
                return forced.contains(name) || super.accept(name);
            }
        });
    }

    @Override
    protected SeContainer newContainer(final WebBeansContext context) {
        final Meecrowave meecrowave = new Meecrowave(builder);
        return new OWBContainer(context, meecrowave) {
            {
                meecrowave.bake();
            }

            @Override
            protected void doClose() {
                meecrowave.close();
            }
        };
    }

    @Override
    protected ScannerService getScannerService() {
        return new OWBTomcatWebScannerService(scannerService);
    }
}
