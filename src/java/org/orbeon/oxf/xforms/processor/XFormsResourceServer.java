/**
 *  Copyright (C) 2007 Orbeon, Inc.
 *
 *  This program is free software; you can redistribute it and/or modify it under the terms of the
 *  GNU Lesser General Public License as published by the Free Software Foundation; either version
 *  2.1 of the License, or (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 *  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 *  See the GNU Lesser General Public License for more details.
 *
 *  The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
 */
package org.orbeon.oxf.xforms.processor;

import org.orbeon.oxf.common.OXFException;
import org.orbeon.oxf.pipeline.api.ExternalContext;
import org.orbeon.oxf.pipeline.api.PipelineContext;
import org.orbeon.oxf.resources.ResourceManagerWrapper;
import org.orbeon.oxf.util.NetUtils;
import org.orbeon.oxf.processor.ProcessorImpl;
import org.orbeon.oxf.xforms.XFormsUtils;

import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;

/**
 * Serve XForms engine JavaScript and CSS resources by combining them.
 */
public class XFormsResourceServer extends ProcessorImpl {

    public XFormsResourceServer() {
    }

    public void start(PipelineContext context) {
        final ExternalContext externalContext = (ExternalContext) context.getAttribute(org.orbeon.oxf.pipeline.api.PipelineContext.EXTERNAL_CONTEXT);
        final ExternalContext.Request request = externalContext.getRequest();
        final ExternalContext.Response response = externalContext.getResponse();

        final String requestPath = request.getRequestPath();
        final String filename = requestPath.substring(requestPath.lastIndexOf('/') + 1);
        final boolean isCSS = filename.endsWith(".css");

        // Find what features are requested
        // Assume a file name of the form: xforms-feature1-feature2-feature3-...[-min].[css|js]
        boolean isMinimal = false;
        final Map requestedFeaturesMap = new HashMap();
        {
            final StringTokenizer st = new StringTokenizer(filename.substring(0, filename.lastIndexOf(".")), "-");
            while (st.hasMoreTokens()) {
                final String currentToken = st.nextToken();

                if (currentToken.equals("min")) {
                    isMinimal = true;
                    continue;
                }

                final XFormsFeatures.FeatureConfig currentFeature = XFormsFeatures.getFeatureById(currentToken);
                if (currentFeature != null)
                    requestedFeaturesMap.put(currentFeature.getName(), currentFeature);
            }
        }

        // Determine list of resources to load
        final List resources;
        if (isCSS)
            resources = XFormsFeatures.getCSSResourcesByFeatureMap(requestedFeaturesMap);
        else
            resources = XFormsFeatures.getJavaScriptResourcesByFeatureMap(requestedFeaturesMap);

        // Get last modified date
        long combinedLastModified = 0;
        for (Iterator i = resources.iterator(); i.hasNext();) {
            final XFormsFeatures.ResourceConfig resourceConfig = (XFormsFeatures.ResourceConfig) i.next();

            final long lastModified = ResourceManagerWrapper.instance().lastModified(resourceConfig.getResourcePath(isMinimal), false);
            if (lastModified > combinedLastModified)
                combinedLastModified = lastModified;
        }

        // If conditional get and date ok, send not modified

        // Set Last-Modified, required for caching and conditional get
        response.setCaching(combinedLastModified, false, false);

        // Check If-Modified-Since and don't return content if condition is met
        if (!response.checkIfModifiedSince(combinedLastModified, false)) {
            response.setStatus(ExternalContext.SC_NOT_MODIFIED);
            return;
        }

        try {
            response.setContentType(isCSS ? "text/css" : "application/javascript");
            final OutputStream responseOutputStream = response.getOutputStream();
            {
                final boolean cacheCombinedResources = XFormsUtils.isCacheCombinedResources();
                final String realPath = (cacheCombinedResources) ? ResourceManagerWrapper.instance().getRealPath(requestPath) : null;
                if (realPath != null) {
                    // We hope to be able to cache as a resource
                    final File resourceFile = new File(realPath);
                    if (resourceFile.exists()) {
                        final long resourceLastModified = resourceFile.lastModified();
                        if (resourceLastModified < combinedLastModified) {
                            // Resource is out of date, generate
                            final FileOutputStream fos = new FileOutputStream(resourceFile);
                            generate(resources, response, fos, isCSS, isMinimal);
                            fos.close();
                        }
                    } else {
                        // Resource doesn't exist, generate
                        resourceFile.getParentFile().mkdirs();
                        resourceFile.createNewFile();
                        final FileOutputStream fos = new FileOutputStream(resourceFile);
                        generate(resources, response, fos, isCSS, isMinimal);
                        fos.close();
                    }

                    final FileInputStream fis = new FileInputStream(resourceFile);
                    NetUtils.copyStream(fis, responseOutputStream);
                    fis.close();
                    responseOutputStream.flush();
                } else {
                    // Otherwise, don't try the cache and send content directly
                    generate(resources, response, responseOutputStream, isCSS, isMinimal);
                }
            }
        } catch (Exception e) {
            throw new OXFException(e);
        }
    }

    private void generate(List resources, ExternalContext.Response response, OutputStream os, boolean CSS, boolean minimal) throws URISyntaxException, IOException {
        if (CSS) {
            // CSS, rewrite content
            final URI applicationBaseURI;
            {
                final String applicationBase = response.rewriteResourceURL("/", true);
                applicationBaseURI = new URI(applicationBase);
            }

            final Writer outputWriter = new OutputStreamWriter(os, "utf-8");
            for (Iterator i = resources.iterator(); i.hasNext();) {
                final XFormsFeatures.ResourceConfig resourceConfig = (XFormsFeatures.ResourceConfig) i.next();
                final String resourcePath = resourceConfig.getResourcePath(minimal);
                final InputStream is = ResourceManagerWrapper.instance().getContentAsStream(resourcePath);

                final String content;
                {
                    final Reader reader = new InputStreamReader(is, "utf-8");
                    final StringWriter stringWriter = new StringWriter();
                    NetUtils.copyStream(reader, stringWriter);
                    reader.close();
                    content = stringWriter.toString();
                }

                final URI resourceURI = applicationBaseURI.resolve(resourcePath.startsWith("/") ? resourcePath.substring(1) : resourcePath);
                {
                    int index = 0;
                    while (true) {
                        final int newIndex = content.indexOf("url(", index);

                        if (newIndex == -1) {
                            // Output remainder
                            if (index == 0)
                                outputWriter.write(content);
                            else
                                outputWriter.write(content.substring(index));
                            break;
                        } else {
                            // output so far
                            outputWriter.write(content.substring(index, newIndex));
                        }

                        // Get URL
                        final String url;
                        {
                            final int closingIndex = content.indexOf(")", newIndex + 4);
                            if (closingIndex == -1)
                                throw new OXFException("Missing closing parenthesis in url() in resource: " + resourceConfig.getResourcePath(minimal));

                            url = content.substring(newIndex + 4, closingIndex);
                            index = closingIndex + 1;
                        }
                        // Rewrite URL and output it as an absolute path
                        final URI resolvedURI = resourceURI.resolve(url.trim());
                        outputWriter.write("url(" + resolvedURI.getPath() + ")");
                    }
                }
            }
            outputWriter.flush();
        } else {
            // JavaScript, just send
            int index = 0;
            for (Iterator i = resources.iterator(); i.hasNext(); index++) {
                final XFormsFeatures.ResourceConfig resourceConfig = (XFormsFeatures.ResourceConfig) i.next();
                final InputStream is = ResourceManagerWrapper.instance().getContentAsStream(resourceConfig.getResourcePath(minimal));
                // Line break seems to help. We assume that the encoding is compatible with ASCII/UTF-8
                if (index > 0)
                    os.write((byte) '\n');
                NetUtils.copyStream(is, os);
                is.close();
            }
        }
        os.flush();
    }
}