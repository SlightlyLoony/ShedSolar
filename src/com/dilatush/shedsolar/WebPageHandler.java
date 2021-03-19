package com.dilatush.shedsolar;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Provides a simple handler for the one web page provided by this server.
 *
 * @author Tom Dilatush  tom@dilatush.com
 */
public class WebPageHandler extends AbstractHandler implements Handler {


    @Override
    public void handle( final String _s, final Request _request, final HttpServletRequest _httpServletRequest,
                        final HttpServletResponse _httpServletResponse ) throws IOException, ServletException {

    }
}
