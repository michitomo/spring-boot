/*
 * Copyright 2012-2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.boot.builder;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.support.BeanNameGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.initializer.ParentContextApplicationContextInitializer;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.util.StringUtils;

/**
 * Builder for {@link SpringApplication} and {@link ApplicationContext} instances with
 * convenient fluent API and context hierarchy support. Simple example of a context
 * hierarchy:
 * 
 * <pre>
 * new SpringApplicationBuilder(ParentConfig.class).child(ChildConfig.class).run(args);
 * </pre>
 * 
 * Another common use case is setting default arguments, e.g. active Spring profiles, to
 * set up the environment for an application:
 * 
 * <pre>
 * new SpringApplicationBuilder(Application.class).profiles(&quot;server&quot;)
 * 		.defaultArgs(&quot;--transport=local&quot;).run(args);
 * </pre>
 * 
 * <p>
 * If your needs are simpler, consider using the static convenience methods in
 * SpringApplication instead.
 * 
 * @author Dave Syer
 * 
 */
public class SpringApplicationBuilder {

	private SpringApplication application;
	private ConfigurableApplicationContext context;
	private SpringApplicationBuilder parent;
	private AtomicBoolean running = new AtomicBoolean(false);
	private Set<Object> sources = new LinkedHashSet<Object>();
	private Set<String> defaultArgs = new LinkedHashSet<String>();
	private ConfigurableEnvironment environment;

	public SpringApplicationBuilder(Object... sources) {
		this.application = new SpringApplication(sources);
	}

	/**
	 * Accessor for the current application context.
	 * 
	 * @return the current application context (or null if not yet running)
	 */
	public ConfigurableApplicationContext context() {
		return this.context;
	}

	/**
	 * Accessor for the current application.
	 * 
	 * @return the current application (never null)
	 */
	public SpringApplication application() {
		return this.application;
	}

	/**
	 * Create an application context (and its parent if specified) with the command line
	 * args provided. The parent is run first with the same arguments if has not yet been
	 * started.
	 * 
	 * @param args the command line arguments
	 * @return an application context created from the current state
	 */
	public ConfigurableApplicationContext run(String... args) {

		if (this.parent != null) {
			// If there is a parent initialize it and make sure it is added to the current
			// context
			addInitializers(true, new ParentContextApplicationContextInitializer(
					this.parent.run(args)));
		}

		if (this.running.get()) {
			// If already created we just return the existing context
			return this.context;
		}

		if (this.running.compareAndSet(false, true)) {
			synchronized (this.running) {
				// If not already running copy the sources over and then run.
				// this.application.setDefaultArgs(this.defaultArgs
				// .toArray(new String[this.defaultArgs.size()]));
				this.application.setSources(this.sources);
				this.context = this.application.run(args);
			}
		}

		return this.context;

	}

	/**
	 * Create a child application with the provided sources. Default args and environment
	 * are copied down into the child, but everything else is a clean sheet.
	 * 
	 * @param sources the sources for the application (Spring configuration)
	 * @return the child application nuilder
	 */
	public SpringApplicationBuilder child(Object... sources) {

		SpringApplicationBuilder child = new SpringApplicationBuilder();
		child.sources(sources);

		// Copy environment stuff from parent to child
		child.defaultArgs(this.defaultArgs).environment(this.environment);
		child.parent = this;

		// It's not possible if embedded containers are enabled to support web contexts as
		// parents because the servlets cannot be initialized at the right point in
		// lifecycle.
		web(false);

		// Probably not interested in multiple banners
		showBanner(false);

		// Make sure sources get copied over
		this.application.setSources(this.sources);

		return child;

	}

	/**
	 * Add a parent application with the provided sources. Default args and environment
	 * are copied up into the parent, but everything else is a clean sheet.
	 * 
	 * @param sources the sources for the application (Spring configuration)
	 * @return the parent builder
	 */
	public SpringApplicationBuilder parent(Object... sources) {
		if (this.parent == null) {
			this.parent = new SpringApplicationBuilder(sources).web(false)
					.defaultArgs(this.defaultArgs).environment(this.environment);
		}
		else {
			this.parent.sources(sources);
		}
		return this.parent;
	}

	private SpringApplicationBuilder runAndExtractParent(String... args) {
		if (this.context == null) {
			run(args);
		}
		if (this.parent != null) {
			return this.parent;
		}
		throw new IllegalStateException(
				"No parent defined yet (please use the other overloaded parent methods to set one)");
	}

	/**
	 * Add an already running parent context to an existing application.
	 * 
	 * @param parent the parent context
	 * @return the current builder (not the parent)
	 */
	public SpringApplicationBuilder parent(ConfigurableApplicationContext parent) {
		this.parent = new SpringApplicationBuilder();
		this.parent.context = parent;
		this.parent.running.set(true);
		addInitializers(true, new ParentContextApplicationContextInitializer(parent));
		return this;
	}

	/**
	 * Create a sibling application (one with the same parent). A side effect of calling
	 * this method is that the current application (and its parent) are started.
	 * 
	 * @param sources the sources for the application (Spring configuration)
	 * 
	 * @return the new sibling builder
	 */
	public SpringApplicationBuilder sibling(Object... sources) {
		return runAndExtractParent().child(sources);
	}

	/**
	 * Create a sibling application (one with the same parent). A side effect of calling
	 * this method is that the current application (and its parent) are started if they
	 * are not already running.
	 * 
	 * @param sources the sources for the application (Spring configuration)
	 * @param args the command line arguments to use when starting the current app and its
	 * parent
	 * 
	 * @return the new sibling builder
	 */
	public SpringApplicationBuilder sibling(Object[] sources, String... args) {
		return runAndExtractParent(args).child(sources);
	}

	/**
	 * Explicitly set the context class to be used.
	 * 
	 * @param cls the context class to use
	 * @return the current builder
	 */
	public SpringApplicationBuilder contextClass(
			Class<? extends ConfigurableApplicationContext> cls) {
		this.application.setApplicationContextClass(cls);
		return this;
	}

	/**
	 * Add more sources to use in this application.
	 * 
	 * @param sources the sources to add
	 * @return the current builder
	 */
	public SpringApplicationBuilder sources(Object... sources) {
		this.sources.addAll(new LinkedHashSet<Object>(Arrays.asList(sources)));
		return this;
	}

	/**
	 * Add more sources (configuration classes and components) to this application
	 * 
	 * @param sources the sources to add
	 * @return the current builder
	 */
	public SpringApplicationBuilder sources(Class<?>... sources) {
		this.sources.addAll(new LinkedHashSet<Object>(Arrays.asList(sources)));
		return this;
	}

	/**
	 * Flag to explicitly request a web or non-web environment (auto detected based on
	 * classpath if not set).
	 * 
	 * @param webEnvironment the flag to set
	 * @return the current builder
	 */
	public SpringApplicationBuilder web(boolean webEnvironment) {
		this.application.setWebEnvironment(webEnvironment);
		return this;
	}

	/**
	 * Flag to indicate the startup information should be logged.
	 * 
	 * @param logStartupInfo the flag to set. Default true.
	 * @return the current builder
	 */
	public SpringApplicationBuilder logStartupInfo(boolean logStartupInfo) {
		this.application.setLogStartupInfo(logStartupInfo);
		return this;
	}

	/**
	 * Flag to indicate the startup banner should be printed.
	 * 
	 * @param showBanner the flag to set. Default true.
	 * @return the current builder
	 */
	public SpringApplicationBuilder showBanner(boolean showBanner) {
		this.application.setShowBanner(showBanner);
		return this;
	}

	/**
	 * Fixes the main application class that is used to anchor the startup messages.
	 * 
	 * @param mainApplicationClass the class to use.
	 * @return the current builder
	 */
	public SpringApplicationBuilder main(Class<?> mainApplicationClass) {
		this.application.setMainApplicationClass(mainApplicationClass);
		return this;
	}

	/**
	 * Flag to indicate that command line arguments should be added to the environment.
	 * 
	 * @param addCommandLineProperties the flag to set. Default true.
	 * @return the current builder
	 */
	public SpringApplicationBuilder addCommandLineProperties(
			boolean addCommandLineProperties) {
		this.application.setAddCommandLineProperties(addCommandLineProperties);
		return this;
	}

	/**
	 * Default command line arguments (overridden by explicit arguments at runtime in
	 * {@link #run(String...)}).
	 * 
	 * @param defaultArgs the args to set.
	 * @return the current builder
	 */
	public SpringApplicationBuilder defaultArgs(String... defaultArgs) {
		this.defaultArgs.addAll(Arrays.asList(defaultArgs));
		this.application.setDefaultArgs(this.defaultArgs
				.toArray(new String[this.defaultArgs.size()]));
		if (this.parent != null) {
			this.parent.defaultArgs(defaultArgs);
			this.parent.environment(this.environment);
		}
		return this;
	}

	private SpringApplicationBuilder defaultArgs(Set<String> defaultArgs) {
		this.defaultArgs = defaultArgs;
		return this;
	}

	/**
	 * Set the active Spring profiles for this app (and its parent and children).
	 * Synonymous with {@link #defaultArgs(String...)
	 * defaultArgs("--spring.profiles.active=[profiles]")}
	 * 
	 * @param profiles the profiles to set.
	 * @return the current builder
	 */
	public SpringApplicationBuilder profiles(String... profiles) {
		defaultArgs("--spring.profiles.active="
				+ StringUtils.arrayToCommaDelimitedString(profiles));
		return this;
	}

	/**
	 * Bean name generator for automatically generated bean names in the application
	 * context.
	 * 
	 * @param beanNameGenerator the generator to set.
	 * @return the current builder
	 */
	public SpringApplicationBuilder beanNameGenerator(BeanNameGenerator beanNameGenerator) {
		this.application.setBeanNameGenerator(beanNameGenerator);
		return this;
	}

	/**
	 * Environment for the application context.
	 * 
	 * @param environment the environment to set.
	 * @return the current builder
	 */
	public SpringApplicationBuilder environment(ConfigurableEnvironment environment) {
		this.application.setEnvironment(environment);
		this.environment = environment;
		return this;
	}

	/**
	 * {@link ResourceLoader} for the application context. If a custom class loader is
	 * needed, this is where it would be added.
	 * 
	 * @param resourceLoader the resource loader to set.
	 * @return the current builder
	 */
	public SpringApplicationBuilder resourceLoader(ResourceLoader resourceLoader) {
		this.application.setResourceLoader(resourceLoader);
		return this;
	}

	/**
	 * Add some initializers to the application (applied to the {@link ApplicationContext}
	 * before any bean definitions are loaded).
	 * 
	 * @param initializers some initializers to add
	 * @return the current builder
	 */
	public SpringApplicationBuilder initializers(
			ApplicationContextInitializer<?>... initializers) {
		addInitializers(false, initializers);
		return this;
	}

	/**
	 * @param initializers the initializers to add
	 */
	private void addInitializers(boolean prepend,
			ApplicationContextInitializer<?>... initializers) {
		Set<ApplicationContextInitializer<?>> target = new LinkedHashSet<ApplicationContextInitializer<?>>();
		if (prepend) {
			target.addAll(Arrays.asList(initializers));
			target.addAll(this.application.getInitializers());
		}
		else {
			target.addAll(this.application.getInitializers());
			target.addAll(Arrays.asList(initializers));
		}
		this.application.setInitializers(target);
	}

}
