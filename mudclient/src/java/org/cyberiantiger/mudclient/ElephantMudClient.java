package org.cyberiantiger.mudclient;

import java.util.*;
import java.io.*;
import org.cyberiantiger.console.*;
import org.cyberiantiger.mudclient.config.*;
import org.cyberiantiger.mudclient.parser.*;
import org.cyberiantiger.mudclient.net.*;
import org.cyberiantiger.mudclient.ui.ControlWindow;
import org.cyberiantiger.mudclient.ui.Connection;

public class ElephantMudClient implements Display, Connection {

    private boolean echo = true;
    private ControlWindow control;
    private MudConnection connection;
    private ConsoleWriter writer;

    private ClientConfiguration config;

    public ElephantMudClient(ClientConfiguration config) {
	this.config = config;
	writer = new ElephantConsoleWriter();
	connection = new MudConnection(this);
	control = new ControlWindow(this);
	control.show();
    }

    public void exit() {
	connection.disconnect();
	connection.stop(); // Deprecated.
	control.hide();
	System.exit(0); // Don't like using this method.
    }

    public void connect() {
	connection.setParser(new ANSIParser());
	connection.connect();
    }
    
    public void disconnect() {
	connection.disconnect();
    }


    public void command(String sourceId, String msg) {
	connection.command(msg);
	if(echo) {
	    ConsoleWriter ui = control.getCurrentView();
	    ui.consoleAction(
		    new StringConsoleAction(msg.toCharArray(),0,msg.length())
		    );
	    ui.consoleAction(
		    new SetCursorXConsoleAction(0)
		    );
	    ui.consoleAction(
		    new MoveCursorYConsoleAction(1)
		    );
	}
    }

    public void setWindowSize(int w, int h) {
	connection.setWindowSize(w,h);
    }

    public void connectionStatusChanged(int newStatus) {
	control.connectionStatusChanged(newStatus);
    }

    public void connectionDoLocalEcho(boolean echo) {
	this.echo = echo;
    }

    public ClientConfiguration getConfiguration() {
	return config;
    }

    public ConsoleWriter getConsoleWriter() {
	return writer;
    }

    private class ElephantConsoleWriter implements ConsoleWriter {

	protected ConsoleWriter getView(String name) {
	    if(name.equals(ClientConfiguration.DEFAULT_VIEW)) {
		return control.getDefaultView();
	    } else if(name.equals(ClientConfiguration.CURRENT_VIEW)) {
		return control.getCurrentView();
	    } else {
		return control.getView(name);
	    }
	}

	public void consoleAction(ConsoleAction action) {
	    if(action instanceof ElephantMUDConsoleAction) {
		ElephantMUDConsoleAction eAction =
		(ElephantMUDConsoleAction) action;

		String pClass = eAction.getPrimaryClass();

		Set dests = config.getOutputFor(pClass);


		if(dests == null) {
		    control.getDefaultView().consoleAction(action);
		} else {
		    Set tmp = new HashSet();
		    Iterator i = dests.iterator();
		    while(i.hasNext()) {
			tmp.add(getView((String)i.next()));
		    }

		    tmp.remove(null);

		    i = tmp.iterator();
		    while(i.hasNext()) {
			((ConsoleWriter)i.next()).consoleAction(action);
		    }
		}

	    } else {
		control.getDefaultView().consoleAction(action);
	    }
	}
    }

    public static void main(String[] args) {
	if(args.length == 1) {
	    try {
		ClientConfiguration config = new ClientConfiguration();
		config.load(new FileInputStream(args[0]));
		new ElephantMudClient(config);
	    } catch (IOException ioe) {
		ioe.printStackTrace();
	    }
	}  else {
	    try {
		ClientConfiguration config = new ClientConfiguration();
		config.load(
			ElephantMudClient.class.getResourceAsStream(
			    "/org/cyberiantiger/mudclient/config.properties"
			    )
			);
		new ElephantMudClient(config);
	    } catch (IOException ioe) {
		ioe.printStackTrace();
	    }
	}
    }
}
