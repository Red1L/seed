/**
 * Copyright (c) 2013-2015, The SeedStack authors <http://seedstack.org>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.seedstack.seed.rest.fixtures;

import org.seedstack.seed.rest.spi.RootResource;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

public class HTMLRootSubResource implements RootResource {
    @Override
    public Response buildResponse(HttpServletRequest httpServletRequest, UriInfo uriInfo) {
        return Response.ok("<h1>Hello World!</h1>").type(MediaType.TEXT_HTML_TYPE).build();
    }
}