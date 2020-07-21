/*******************************************************************************
 * Copyright (c) 2019 Red Hat Inc. and others.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.wildwebdeveloper.yaml;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.jface.util.PropertyChangeEvent;
import org.eclipse.lsp4e.LanguageServersRegistry;
import org.eclipse.lsp4e.LanguageServersRegistry.LanguageServerDefinition;
import org.eclipse.lsp4e.LanguageServiceAccessor;
import org.eclipse.lsp4e.server.ProcessStreamConnectionProvider;
import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeWorkspaceFoldersParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.WorkspaceFoldersChangeEvent;
import org.eclipse.lsp4j.jsonrpc.messages.Message;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseMessage;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.wildwebdeveloper.Activator;
import org.eclipse.wildwebdeveloper.embedder.node.NodeJSManager;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

@SuppressWarnings("restriction")
public class YAMLLanguageServer extends ProcessStreamConnectionProvider {
	private static final String SETTINGS_KEY = "settings";
	private static final String YAML_KEY = "yaml";
	private static final String VALIDATE_KEY = "validate";
	private static final String COMPLETION_KEY = "completion";
	private static final String HOVER_KEY = "hover";
	private static final String SCHEMAS_KEY = "schemas";
	
 	private static final IPreferenceStore store = Activator.getDefault().getPreferenceStore();
 	private static final LanguageServerDefinition yamlLsDefinition = LanguageServersRegistry.getInstance().getDefinition("org.eclipse.wildwebdeveloper.yaml");
 	private static final IPropertyChangeListener psListener = new IPropertyChangeListener() {
		@Override
		public void propertyChange(PropertyChangeEvent event) {
			if (YAMLPreferenceInitializer.YAML_SCHEMA_PREFERENCE.equals(event.getProperty())) {
				Map<String, Object> yaml = getYamlSchemaOptions();
				Map<String, Object> settings = new HashMap<>();
				settings.put(YAML_KEY, yaml);
				Map<String, Object> options = new HashMap<>();
				options.put(SETTINGS_KEY, settings);
				DidChangeConfigurationParams params = new DidChangeConfigurationParams(settings);
				LanguageServiceAccessor.getActiveLanguageServers(null).stream().filter(server -> yamlLsDefinition.equals(LanguageServiceAccessor.resolveServerDefinition(server).get()))
					.forEach(ls -> ls.getWorkspaceService().didChangeConfiguration(params));
			}
		}
 	};
 	
 	private static final IResourceChangeListener wsFoldersListener = new IResourceChangeListener() {
		@Override
		public void resourceChanged(IResourceChangeEvent event) {
			if ((event.getDelta().getFlags() ^ IResourceDelta.MARKERS) != 0) {
				final List<IResource> added = new ArrayList<>();
				final List<IResource> removed = new ArrayList<>();
			
				try {
					event.getDelta().accept(delta -> {
						if (delta.getResource().getType() == IResource.PROJECT) {
							if (delta.getKind() == IResourceDelta.ADDED)  {
								added.add(delta.getResource());
							} else if (delta.getKind() == IResourceDelta.REMOVED) {
								removed.add(delta.getResource());
							}
						}
						return true;
					});
				} catch (CoreException e) {
					Activator.getDefault().getLog().log(
							new Status(IStatus.ERROR, Activator.getDefault().getBundle().getSymbolicName(), e.getMessage(), e));
				}
				if (!added.isEmpty() || !removed.isEmpty()) {
					WorkspaceFoldersChangeEvent wsFoldersChangeEvent = 
							new WorkspaceFoldersChangeEvent(
									toWorkspaceFolders(added), 
									toWorkspaceFolders(removed));
					DidChangeWorkspaceFoldersParams wsFolderParams = new DidChangeWorkspaceFoldersParams(wsFoldersChangeEvent);
					LanguageServiceAccessor.getActiveLanguageServers(null).stream().filter(server -> yamlLsDefinition.equals(LanguageServiceAccessor.resolveServerDefinition(server).get()))
						.forEach(ls -> ls.getWorkspaceService().didChangeWorkspaceFolders(wsFolderParams));
				}
			}
		}
	};
 	
	static {
		store.addPropertyChangeListener(psListener);
		ResourcesPlugin.getWorkspace().addResourceChangeListener(wsFoldersListener, IResourceChangeEvent.POST_CHANGE);
	}
	
	public YAMLLanguageServer() {
		List<String> commands = new ArrayList<>();
		commands.add(NodeJSManager.getNodeJsLocation().getAbsolutePath());
		try {
			URL url = FileLocator.toFileURL(getClass()
					.getResource("/node_modules/yaml-language-server/out/server/src/server.js"));
			commands.add(new java.io.File(url.getPath()).getAbsolutePath());
			commands.add("--stdio");
			setCommands(commands);
			setWorkingDirectory(System.getProperty("user.dir"));
		} catch (IOException e) {
			Activator.getDefault().getLog().log(
					new Status(IStatus.ERROR, Activator.getDefault().getBundle().getSymbolicName(), e.getMessage(), e));
		}
	}
	
	@Override
	public void handleMessage(Message message, LanguageServer languageServer, URI rootUri) {
		if (message instanceof ResponseMessage) {
			ResponseMessage responseMessage = (ResponseMessage) message;
			if (responseMessage.getResult() instanceof InitializeResult) {
				Map<String, Object> settings = new HashMap<>();
				settings.put(YAML_KEY, getYamlSchemaOptions());
				
				DidChangeConfigurationParams params = new DidChangeConfigurationParams(settings);
				languageServer.getWorkspaceService().didChangeConfiguration(params);
				
				WorkspaceFoldersChangeEvent event = new WorkspaceFoldersChangeEvent(
						toWorkspaceFolders(getInitialWorkspaceFolders()), 
						Collections.emptyList());
				DidChangeWorkspaceFoldersParams wsFolderParams = new DidChangeWorkspaceFoldersParams(event);
				languageServer.getWorkspaceService().didChangeWorkspaceFolders(wsFolderParams);
			}
		}
	}

	private static Map<String, Object> getYamlSchemaOptions() {
		Map<String, Object> yaml = new HashMap<>();
		IPreferenceStore preferenceStore = Activator.getDefault().getPreferenceStore();
		String schemaStr = preferenceStore.getString(YAMLPreferenceInitializer.YAML_SCHEMA_PREFERENCE);
		if (!schemaStr.trim().isEmpty()) {
			Map<String, Object> schemas = new Gson().fromJson(schemaStr, new TypeToken<HashMap<String, Object>>() {}.getType());
			yaml.put(SCHEMAS_KEY, schemas);
			yaml.put(VALIDATE_KEY, true);
			yaml.put(COMPLETION_KEY, true);
			yaml.put(HOVER_KEY, true);
		}
		return yaml;
	}

	private static List<IResource> getInitialWorkspaceFolders() {
		List<IResource> wsFolders = new ArrayList<>();
		Arrays.asList(ResourcesPlugin.getWorkspace().getRoot().getProjects())
			.forEach(p -> {
				try {
					p.accept(r -> {
						if (r.isAccessible() && !r.isHidden() && r.getType() == IResource.PROJECT &&
								!r.getName().startsWith(".")) {
							wsFolders.add(r);
						}
						return true;
					});
				} catch (CoreException e) {
					Activator.getDefault().getLog().log(
							new Status(IStatus.ERROR, Activator.getDefault().getBundle().getSymbolicName(), e.getMessage(), e));
				}
			});
		return wsFolders;
	}
	
	private static List<WorkspaceFolder> toWorkspaceFolders(List<IResource> folders) {
		List<WorkspaceFolder> wsFolders = new ArrayList<>();
		folders.forEach(p -> wsFolders.add(new WorkspaceFolder(p.getLocationURI().toString())));
		return wsFolders;
	}

	@Override
	public String toString() {
		return "YAML Language Server: " + super.toString();
	}

	@Override
	protected void finalize() throws Throwable {
    	ResourcesPlugin.getWorkspace().removeResourceChangeListener(wsFoldersListener);
		store.removePropertyChangeListener(psListener);
		super.finalize();
	}
}
