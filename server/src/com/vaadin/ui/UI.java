/*
 * Copyright 2000-2013 Vaadin Ltd.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.vaadin.ui;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.concurrent.Future;

import com.vaadin.event.Action;
import com.vaadin.event.Action.Handler;
import com.vaadin.event.ActionManager;
import com.vaadin.event.MouseEvents.ClickEvent;
import com.vaadin.event.MouseEvents.ClickListener;
import com.vaadin.navigator.Navigator;
import com.vaadin.server.Page;
import com.vaadin.server.PaintException;
import com.vaadin.server.PaintTarget;
import com.vaadin.server.UIProvider;
import com.vaadin.server.VaadinRequest;
import com.vaadin.server.VaadinService;
import com.vaadin.server.VaadinServlet;
import com.vaadin.server.VaadinSession;
import com.vaadin.server.communication.PushConnection;
import com.vaadin.shared.EventId;
import com.vaadin.shared.MouseEventDetails;
import com.vaadin.shared.communication.PushMode;
import com.vaadin.shared.ui.ui.ScrollClientRpc;
import com.vaadin.shared.ui.ui.UIClientRpc;
import com.vaadin.shared.ui.ui.UIConstants;
import com.vaadin.shared.ui.ui.UIServerRpc;
import com.vaadin.shared.ui.ui.UIState;
import com.vaadin.ui.Component.Focusable;
import com.vaadin.util.CurrentInstance;

/**
 * The topmost component in any component hierarchy. There is one UI for every
 * Vaadin instance in a browser window. A UI may either represent an entire
 * browser window (or tab) or some part of a html page where a Vaadin
 * application is embedded.
 * <p>
 * The UI is the server side entry point for various client side features that
 * are not represented as components added to a layout, e.g notifications, sub
 * windows, and executing javascript in the browser.
 * </p>
 * <p>
 * When a new UI instance is needed, typically because the user opens a URL in a
 * browser window which points to e.g. {@link VaadinServlet}, all
 * {@link UIProvider}s registered to the current {@link VaadinSession} are
 * queried for the UI class that should be used. The selection is by defaylt
 * based on the {@value VaadinSession#UI_PARAMETER} parameter from web.xml.
 * </p>
 * <p>
 * After a UI has been created by the application, it is initialized using
 * {@link #init(VaadinRequest)}. This method is intended to be overridden by the
 * developer to add components to the user interface and initialize
 * non-component functionality. The component hierarchy must be initialized by
 * passing a {@link Component} with the main layout or other content of the view
 * to {@link #setContent(Component)} or to the constructor of the UI.
 * </p>
 * 
 * @see #init(VaadinRequest)
 * @see UIProvider
 * 
 * @since 7.0
 */
public abstract class UI extends AbstractSingleComponentContainer implements
        Action.Container, Action.Notifier, LegacyComponent, Focusable {

    /**
     * The application to which this UI belongs
     */
    private volatile VaadinSession session;

    /**
     * List of windows in this UI.
     */
    private final LinkedHashSet<Window> windows = new LinkedHashSet<Window>();

    /**
     * The component that should be scrolled into view after the next repaint.
     * Null if nothing should be scrolled into view.
     */
    private Component scrollIntoView;

    /**
     * The id of this UI, used to find the server side instance of the UI form
     * which a request originates. A negative value indicates that the UI id has
     * not yet been assigned by the Application.
     * 
     * @see VaadinSession#getNextUIid()
     */
    private int uiId = -1;

    /**
     * Keeps track of the Actions added to this component, and manages the
     * painting and handling as well.
     */
    protected ActionManager actionManager;

    /** Identifies the click event */
    private ConnectorTracker connectorTracker = new ConnectorTracker(this);

    private Page page = new Page(this, getState(false).pageState);

    private LoadingIndicatorConfiguration loadingIndicatorConfiguration = new LoadingIndicatorConfigurationImpl(
            this);

    /**
     * Scroll Y position.
     */
    private int scrollTop = 0;

    /**
     * Scroll X position
     */
    private int scrollLeft = 0;

    private UIServerRpc rpc = new UIServerRpc() {
        @Override
        public void click(MouseEventDetails mouseDetails) {
            fireEvent(new ClickEvent(UI.this, mouseDetails));
        }

        @Override
        public void resize(int viewWidth, int viewHeight, int windowWidth,
                int windowHeight) {
            // TODO We're not doing anything with the view dimensions
            getPage().updateBrowserWindowSize(windowWidth, windowHeight);
        }

        @Override
        public void scroll(int scrollTop, int scrollLeft) {
            UI.this.scrollTop = scrollTop;
            UI.this.scrollLeft = scrollLeft;
        }

        @Override
        public void poll() {
            /*
             * No-op. This is only called to cause a server visit to check for
             * changes.
             */
        }
    };

    /**
     * Timestamp keeping track of the last heartbeat of this UI. Updated to the
     * current time whenever the application receives a heartbeat or UIDL
     * request from the client for this UI.
     */
    private long lastHeartbeatTimestamp = System.currentTimeMillis();

    private boolean closing = false;

    private TooltipConfiguration tooltipConfiguration = new TooltipConfigurationImpl(
            this);

    /**
     * Creates a new empty UI without a caption. The content of the UI must be
     * set by calling {@link #setContent(Component)} before using the UI.
     */
    public UI() {
        this(null);
    }

    /**
     * Creates a new UI with the given component (often a layout) as its
     * content.
     * 
     * @param content
     *            the component to use as this UIs content.
     * 
     * @see #setContent(Component)
     */
    public UI(Component content) {
        registerRpc(rpc);
        setSizeFull();
        setContent(content);
    }

    @Override
    protected UIState getState() {
        return (UIState) super.getState();
    }

    @Override
    protected UIState getState(boolean markAsDirty) {
        return (UIState) super.getState(markAsDirty);
    }

    @Override
    public Class<? extends UIState> getStateType() {
        // This is a workaround for a problem with creating the correct state
        // object during build
        return UIState.class;
    }

    /**
     * Overridden to return a value instead of referring to the parent.
     * 
     * @return this UI
     * 
     * @see com.vaadin.ui.AbstractComponent#getUI()
     */
    @Override
    public UI getUI() {
        return this;
    }

    /**
     * Gets the application object to which the component is attached.
     * 
     * <p>
     * The method will return {@code null} if the component is not currently
     * attached to an application.
     * </p>
     * 
     * <p>
     * Getting a null value is often a problem in constructors of regular
     * components and in the initializers of custom composite components. A
     * standard workaround is to use {@link VaadinSession#getCurrent()} to
     * retrieve the application instance that the current request relates to.
     * Another way is to move the problematic initialization to
     * {@link #attach()}, as described in the documentation of the method.
     * </p>
     * 
     * @return the parent application of the component or <code>null</code>.
     * @see #attach()
     */
    @Override
    public VaadinSession getSession() {
        return session;
    }

    @Override
    public void paintContent(PaintTarget target) throws PaintException {
        page.paintContent(target);

        if (scrollIntoView != null) {
            target.addAttribute("scrollTo", scrollIntoView);
            scrollIntoView = null;
        }

        if (pendingFocus != null) {
            // ensure focused component is still attached to this main window
            if (pendingFocus.getUI() == this
                    || (pendingFocus.getUI() != null && pendingFocus.getUI()
                            .getParent() == this)) {
                target.addAttribute("focused", pendingFocus);
            }
            pendingFocus = null;
        }

        if (actionManager != null) {
            actionManager.paintActions(null, target);
        }

        if (isResizeLazy()) {
            target.addAttribute(UIConstants.RESIZE_LAZY, true);
        }
    }

    /**
     * Fire a click event to all click listeners.
     * 
     * @param object
     *            The raw "value" of the variable change from the client side.
     */
    private void fireClick(Map<String, Object> parameters) {
        MouseEventDetails mouseDetails = MouseEventDetails
                .deSerialize((String) parameters.get("mouseDetails"));
        fireEvent(new ClickEvent(this, mouseDetails));
    }

    @Override
    @SuppressWarnings("unchecked")
    public void changeVariables(Object source, Map<String, Object> variables) {
        if (variables.containsKey(EventId.CLICK_EVENT_IDENTIFIER)) {
            fireClick((Map<String, Object>) variables
                    .get(EventId.CLICK_EVENT_IDENTIFIER));
        }

        // Actions
        if (actionManager != null) {
            actionManager.handleActions(variables, this);
        }

        if (variables.containsKey(UIConstants.LOCATION_VARIABLE)) {
            String location = (String) variables
                    .get(UIConstants.LOCATION_VARIABLE);
            getPage().updateLocation(location);
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.vaadin.ui.HasComponents#iterator()
     */
    @Override
    public Iterator<Component> iterator() {
        // TODO could directly create some kind of combined iterator instead of
        // creating a new ArrayList
        ArrayList<Component> components = new ArrayList<Component>();

        if (getContent() != null) {
            components.add(getContent());
        }

        components.addAll(windows);

        return components.iterator();
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.vaadin.ui.ComponentContainer#getComponentCount()
     */
    @Override
    public int getComponentCount() {
        return windows.size() + (getContent() == null ? 0 : 1);
    }

    /**
     * Sets the session to which this UI is assigned.
     * <p>
     * This method is for internal use by the framework. To explicitly close a
     * UI, see {@link #close()}.
     * </p>
     * 
     * @param session
     *            the session to set
     * 
     * @throws IllegalStateException
     *             if the session has already been set
     * 
     * @see #getSession()
     */
    public void setSession(VaadinSession session) {
        if ((session == null) == (this.session == null)) {
            throw new IllegalStateException(
                    "VaadinServiceSession has already been set. Old session: "
                            + getSessionDetails(this.session)
                            + ". New session: " + getSessionDetails(session)
                            + ".");
        } else {
            if (session == null) {
                detach();
                if (pushConnection != null && pushConnection.isConnected()) {
                    // Close the push connection when UI is detached. Otherwise
                    // the push connection and possibly VaadinSession will live
                    // on.
                    pushConnection.disconnect();
                }
            }
            this.session = session;
        }

        if (session != null) {
            attach();
        }
    }

    private static String getSessionDetails(VaadinSession session) {
        if (session == null) {
            return null;
        } else {
            return session.toString() + " for "
                    + session.getService().getServiceName();
        }
    }

    /**
     * Gets the id of the UI, used to identify this UI within its application
     * when processing requests. The UI id should be present in every request to
     * the server that originates from this UI.
     * {@link VaadinService#findUI(VaadinRequest)} uses this id to find the
     * route to which the request belongs.
     * 
     * @return
     */
    public int getUIId() {
        return uiId;
    }

    /**
     * Adds a window as a subwindow inside this UI. To open a new browser window
     * or tab, you should instead use a {@link UIProvider}.
     * 
     * @param window
     * @throws IllegalArgumentException
     *             if the window is already added to an application
     * @throws NullPointerException
     *             if the given <code>Window</code> is <code>null</code>.
     */
    public void addWindow(Window window) throws IllegalArgumentException,
            NullPointerException {

        if (window == null) {
            throw new NullPointerException("Argument must not be null");
        }

        if (window.isAttached()) {
            throw new IllegalArgumentException(
                    "Window is already attached to an application.");
        }

        attachWindow(window);
    }

    /**
     * Helper method to attach a window.
     * 
     * @param w
     *            the window to add
     */
    private void attachWindow(Window w) {
        windows.add(w);
        w.setParent(this);
        markAsDirty();
    }

    /**
     * Remove the given subwindow from this UI.
     * 
     * Since Vaadin 6.5, {@link Window.CloseListener}s are called also when
     * explicitly removing a window by calling this method.
     * 
     * Since Vaadin 6.5, returns a boolean indicating if the window was removed
     * or not.
     * 
     * @param window
     *            Window to be removed.
     * @return true if the subwindow was removed, false otherwise
     */
    public boolean removeWindow(Window window) {
        if (!windows.remove(window)) {
            // Window window is not a subwindow of this UI.
            return false;
        }
        window.setParent(null);
        markAsDirty();
        window.fireClose();

        return true;
    }

    /**
     * Gets all the windows added to this UI.
     * 
     * @return an unmodifiable collection of windows
     */
    public Collection<Window> getWindows() {
        return Collections.unmodifiableCollection(windows);
    }

    @Override
    public void focus() {
        super.focus();
    }

    /**
     * Component that should be focused after the next repaint. Null if no focus
     * change should take place.
     */
    private Focusable pendingFocus;

    private boolean resizeLazy = false;

    private String theme;

    private Navigator navigator;

    private PushConnection pushConnection = null;

    private boolean hasPendingPush = false;

    /**
     * This method is used by Component.Focusable objects to request focus to
     * themselves. Focus renders must be handled at window level (instead of
     * Component.Focusable) due we want the last focused component to be focused
     * in client too. Not the one that is rendered last (the case we'd get if
     * implemented in Focusable only).
     * 
     * To focus component from Vaadin application, use Focusable.focus(). See
     * {@link Focusable}.
     * 
     * @param focusable
     *            to be focused on next paint
     */
    public void setFocusedComponent(Focusable focusable) {
        pendingFocus = focusable;
        markAsDirty();
    }

    /**
     * Scrolls any component between the component and UI to a suitable position
     * so the component is visible to the user. The given component must belong
     * to this UI.
     * 
     * @param component
     *            the component to be scrolled into view
     * @throws IllegalArgumentException
     *             if {@code component} does not belong to this UI
     */
    public void scrollIntoView(Component component)
            throws IllegalArgumentException {
        if (component.getUI() != this) {
            throw new IllegalArgumentException(
                    "The component where to scroll must belong to this UI.");
        }
        scrollIntoView = component;
        markAsDirty();
    }

    /**
     * Internal initialization method, should not be overridden. This method is
     * not declared as final because that would break compatibility with e.g.
     * CDI.
     * 
     * @param request
     *            the initialization request
     * @param uiId
     *            the id of the new ui
     */
    public void doInit(VaadinRequest request, int uiId) {
        if (this.uiId != -1) {
            throw new IllegalStateException("UI id has already been defined");
        }
        this.uiId = uiId;

        // Actual theme - used for finding CustomLayout templates
        theme = request.getParameter("theme");

        getPage().init(request);

        // Call the init overridden by the application developer
        init(request);

        Navigator navigator = getNavigator();
        if (navigator != null) {
            // Kickstart navigation if a navigator was attached in init()
            navigator.navigateTo(navigator.getState());
        }
    }

    /**
     * Initializes this UI. This method is intended to be overridden by
     * subclasses to build the view and configure non-component functionality.
     * Performing the initialization in a constructor is not suggested as the
     * state of the UI is not properly set up when the constructor is invoked.
     * <p>
     * The {@link VaadinRequest} can be used to get information about the
     * request that caused this UI to be created.
     * </p>
     * 
     * @param request
     *            the Vaadin request that caused this UI to be created
     */
    protected abstract void init(VaadinRequest request);

    /**
     * Sets the thread local for the current UI. This method is used by the
     * framework to set the current application whenever a new request is
     * processed and it is cleared when the request has been processed.
     * <p>
     * The application developer can also use this method to define the current
     * UI outside the normal request handling, e.g. when initiating custom
     * background threads.
     * </p>
     * 
     * @param uI
     *            the UI to register as the current UI
     * 
     * @see #getCurrent()
     * @see ThreadLocal
     */
    public static void setCurrent(UI ui) {
        CurrentInstance.setInheritable(UI.class, ui);
    }

    /**
     * Gets the currently used UI. The current UI is automatically defined when
     * processing requests to the server. In other cases, (e.g. from background
     * threads), the current UI is not automatically defined.
     * 
     * @return the current UI instance if available, otherwise <code>null</code>
     * 
     * @see #setCurrent(UI)
     */
    public static UI getCurrent() {
        return CurrentInstance.get(UI.class);
    }

    /**
     * Set top offset to which the UI should scroll to.
     * 
     * @param scrollTop
     */
    public void setScrollTop(int scrollTop) {
        if (scrollTop < 0) {
            throw new IllegalArgumentException(
                    "Scroll offset must be at least 0");
        }
        if (this.scrollTop != scrollTop) {
            this.scrollTop = scrollTop;
            getRpcProxy(ScrollClientRpc.class).setScrollTop(scrollTop);
        }
    }

    public int getScrollTop() {
        return scrollTop;
    }

    /**
     * Set left offset to which the UI should scroll to.
     * 
     * @param scrollLeft
     */
    public void setScrollLeft(int scrollLeft) {
        if (scrollLeft < 0) {
            throw new IllegalArgumentException(
                    "Scroll offset must be at least 0");
        }
        if (this.scrollLeft != scrollLeft) {
            this.scrollLeft = scrollLeft;
            getRpcProxy(ScrollClientRpc.class).setScrollLeft(scrollLeft);
        }
    }

    public int getScrollLeft() {
        return scrollLeft;
    }

    @Override
    protected ActionManager getActionManager() {
        if (actionManager == null) {
            actionManager = new ActionManager(this);
        }
        return actionManager;
    }

    @Override
    public <T extends Action & com.vaadin.event.Action.Listener> void addAction(
            T action) {
        getActionManager().addAction(action);
    }

    @Override
    public <T extends Action & com.vaadin.event.Action.Listener> void removeAction(
            T action) {
        if (actionManager != null) {
            actionManager.removeAction(action);
        }
    }

    @Override
    public void addActionHandler(Handler actionHandler) {
        getActionManager().addActionHandler(actionHandler);
    }

    @Override
    public void removeActionHandler(Handler actionHandler) {
        if (actionManager != null) {
            actionManager.removeActionHandler(actionHandler);
        }
    }

    /**
     * Should resize operations be lazy, i.e. should there be a delay before
     * layout sizes are recalculated and resize events are sent to the server.
     * Speeds up resize operations in slow UIs with the penalty of slightly
     * decreased usability.
     * <p>
     * Default value: <code>false</code>
     * </p>
     * <p>
     * When there are active window resize listeners, lazy resize mode should be
     * used to avoid a large number of events during resize.
     * </p>
     * 
     * @param resizeLazy
     *            true to use a delay before recalculating sizes, false to
     *            calculate immediately.
     */
    public void setResizeLazy(boolean resizeLazy) {
        this.resizeLazy = resizeLazy;
        markAsDirty();
    }

    /**
     * Checks whether lazy resize is enabled.
     * 
     * @return <code>true</code> if lazy resize is enabled, <code>false</code>
     *         if lazy resize is not enabled
     */
    public boolean isResizeLazy() {
        return resizeLazy;
    }

    /**
     * Add a click listener to the UI. The listener is called whenever the user
     * clicks inside the UI. Also when the click targets a component inside the
     * UI, provided the targeted component does not prevent the click event from
     * propagating.
     * 
     * Use {@link #removeListener(ClickListener)} to remove the listener.
     * 
     * @param listener
     *            The listener to add
     */
    public void addClickListener(ClickListener listener) {
        addListener(EventId.CLICK_EVENT_IDENTIFIER, ClickEvent.class, listener,
                ClickListener.clickMethod);
    }

    /**
     * @deprecated As of 7.0, replaced by
     *             {@link #addClickListener(ClickListener)}
     **/
    @Deprecated
    public void addListener(ClickListener listener) {
        addClickListener(listener);
    }

    /**
     * Remove a click listener from the UI. The listener should earlier have
     * been added using {@link #addListener(ClickListener)}.
     * 
     * @param listener
     *            The listener to remove
     */
    public void removeClickListener(ClickListener listener) {
        removeListener(EventId.CLICK_EVENT_IDENTIFIER, ClickEvent.class,
                listener);
    }

    /**
     * @deprecated As of 7.0, replaced by
     *             {@link #removeClickListener(ClickListener)}
     **/
    @Deprecated
    public void removeListener(ClickListener listener) {
        removeClickListener(listener);
    }

    @Override
    public boolean isConnectorEnabled() {
        // TODO How can a UI be invisible? What does it mean?
        return isVisible() && isEnabled();
    }

    public ConnectorTracker getConnectorTracker() {
        return connectorTracker;
    }

    public Page getPage() {
        return page;
    }

    /**
     * Returns the navigator attached to this UI or null if there is no
     * navigator.
     * 
     * @return
     */
    public Navigator getNavigator() {
        return navigator;
    }

    /**
     * For internal use only.
     * 
     * @param navigator
     */
    public void setNavigator(Navigator navigator) {
        this.navigator = navigator;
    }

    /**
     * Setting the caption of a UI is not supported. To set the title of the
     * HTML page, use Page.setTitle
     * 
     * @deprecated As of 7.0, use {@link Page#setTitle(String)}
     */
    @Override
    @Deprecated
    public void setCaption(String caption) {
        throw new UnsupportedOperationException(
                "You can not set the title of a UI. To set the title of the HTML page, use Page.setTitle");
    }

    /**
     * Shows a notification message on the middle of the UI. The message
     * automatically disappears ("humanized message").
     * 
     * Care should be taken to to avoid XSS vulnerabilities as the caption is
     * rendered as html.
     * 
     * @see #showNotification(Notification)
     * @see Notification
     * 
     * @param caption
     *            The message
     * 
     * @deprecated As of 7.0, use Notification.show instead but be aware that
     *             Notification.show does not allow HTML.
     */
    @Deprecated
    public void showNotification(String caption) {
        Notification notification = new Notification(caption);
        notification.setHtmlContentAllowed(true);// Backwards compatibility
        getPage().showNotification(notification);
    }

    /**
     * Shows a notification message the UI. The position and behavior of the
     * message depends on the type, which is one of the basic types defined in
     * {@link Notification}, for instance Notification.TYPE_WARNING_MESSAGE.
     * 
     * Care should be taken to to avoid XSS vulnerabilities as the caption is
     * rendered as html.
     * 
     * @see #showNotification(Notification)
     * @see Notification
     * 
     * @param caption
     *            The message
     * @param type
     *            The message type
     * 
     * @deprecated As of 7.0, use Notification.show instead but be aware that
     *             Notification.show does not allow HTML.
     */
    @Deprecated
    public void showNotification(String caption, Notification.Type type) {
        Notification notification = new Notification(caption, type);
        notification.setHtmlContentAllowed(true);// Backwards compatibility
        getPage().showNotification(notification);
    }

    /**
     * Shows a notification consisting of a bigger caption and a smaller
     * description on the middle of the UI. The message automatically disappears
     * ("humanized message").
     * 
     * Care should be taken to to avoid XSS vulnerabilities as the caption and
     * description are rendered as html.
     * 
     * @see #showNotification(Notification)
     * @see Notification
     * 
     * @param caption
     *            The caption of the message
     * @param description
     *            The message description
     * 
     * @deprecated As of 7.0, use new Notification(...).show(Page) instead but
     *             be aware that HTML by default not allowed.
     */
    @Deprecated
    public void showNotification(String caption, String description) {
        Notification notification = new Notification(caption, description);
        notification.setHtmlContentAllowed(true);// Backwards compatibility
        getPage().showNotification(notification);
    }

    /**
     * Shows a notification consisting of a bigger caption and a smaller
     * description. The position and behavior of the message depends on the
     * type, which is one of the basic types defined in {@link Notification} ,
     * for instance Notification.TYPE_WARNING_MESSAGE.
     * 
     * Care should be taken to to avoid XSS vulnerabilities as the caption and
     * description are rendered as html.
     * 
     * @see #showNotification(Notification)
     * @see Notification
     * 
     * @param caption
     *            The caption of the message
     * @param description
     *            The message description
     * @param type
     *            The message type
     * 
     * @deprecated As of 7.0, use new Notification(...).show(Page) instead but
     *             be aware that HTML by default not allowed.
     */
    @Deprecated
    public void showNotification(String caption, String description,
            Notification.Type type) {
        Notification notification = new Notification(caption, description, type);
        notification.setHtmlContentAllowed(true);// Backwards compatibility
        getPage().showNotification(notification);
    }

    /**
     * Shows a notification consisting of a bigger caption and a smaller
     * description. The position and behavior of the message depends on the
     * type, which is one of the basic types defined in {@link Notification} ,
     * for instance Notification.TYPE_WARNING_MESSAGE.
     * 
     * Care should be taken to avoid XSS vulnerabilities if html content is
     * allowed.
     * 
     * @see #showNotification(Notification)
     * @see Notification
     * 
     * @param caption
     *            The message caption
     * @param description
     *            The message description
     * @param type
     *            The type of message
     * @param htmlContentAllowed
     *            Whether html in the caption and description should be
     *            displayed as html or as plain text
     * 
     * @deprecated As of 7.0, use new Notification(...).show(Page).
     */
    @Deprecated
    public void showNotification(String caption, String description,
            Notification.Type type, boolean htmlContentAllowed) {
        getPage()
                .showNotification(
                        new Notification(caption, description, type,
                                htmlContentAllowed));
    }

    /**
     * Shows a notification message.
     * 
     * @see Notification
     * @see #showNotification(String)
     * @see #showNotification(String, int)
     * @see #showNotification(String, String)
     * @see #showNotification(String, String, int)
     * 
     * @param notification
     *            The notification message to show
     * 
     * @deprecated As of 7.0, use Notification.show instead
     */
    @Deprecated
    public void showNotification(Notification notification) {
        getPage().showNotification(notification);
    }

    /**
     * Returns the timestamp of the last received heartbeat for this UI.
     * 
     * @see #heartbeat()
     * @see VaadinSession#cleanupInactiveUIs()
     * 
     * @return The time the last heartbeat request occurred, in milliseconds
     *         since the epoch.
     */
    public long getLastHeartbeatTimestamp() {
        return lastHeartbeatTimestamp;
    }

    /**
     * Sets the last heartbeat request timestamp for this UI. Called by the
     * framework whenever the application receives a valid heartbeat request for
     * this UI.
     * 
     * @param lastHeartbeat
     *            The time the last heartbeat request occurred, in milliseconds
     *            since the epoch.
     */
    public void setLastHeartbeatTimestamp(long lastHeartbeat) {
        lastHeartbeatTimestamp = lastHeartbeat;
    }

    /**
     * Gets the theme that was used when the UI was initialized.
     * 
     * @return the theme name
     */
    public String getTheme() {
        return theme;
    }

    /**
     * Marks this UI to be {@link #detach() detached} from the session at the
     * end of the current request, or the next request if there is no current
     * request (if called from a background thread, for instance.)
     * <p>
     * The UI is detached after the response is sent, so in the current request
     * it can still update the client side normally. However, after the response
     * any new requests from the client side to this UI will cause an error, so
     * usually the client should be asked, for instance, to reload the page
     * (serving a fresh UI instance), to close the page, or to navigate
     * somewhere else.
     * <p>
     * Note that this method is strictly for users to explicitly signal the
     * framework that the UI should be detached. Overriding it is not a reliable
     * way to catch UIs that are to be detached. Instead, {@code UI.detach()}
     * should be overridden or a {@link DetachListener} used.
     */
    public void close() {
        closing = true;

        boolean sessionExpired = (session == null || session.isClosing());
        getRpcProxy(UIClientRpc.class).uiClosed(sessionExpired);
        if (getPushConnection() != null && getPushConnection().isConnected()) {
            // Push the Rpc to the client. The connection will be closed when
            // the UI is detached and cleaned up.
            getPushConnection().push();
        }

    }

    /**
     * Returns whether this UI is marked as closed and is to be detached.
     * 
     * @see #close()
     * 
     * @return whether this UI is closing.
     */
    public boolean isClosing() {
        return closing;
    }

    /**
     * Called after the UI is added to the session. A UI instance is attached
     * exactly once, before its {@link #init(VaadinRequest) init} method is
     * called.
     * 
     * @see Component#attach
     */
    @Override
    public void attach() {
        super.attach();
    }

    /**
     * Called before the UI is removed from the session. A UI instance is
     * detached exactly once, either:
     * <ul>
     * <li>after it is explicitly {@link #close() closed}.
     * <li>when its session is closed or expires
     * <li>after three missed heartbeat requests.
     * </ul>
     * <p>
     * Note that when a UI is detached, any changes made in the {@code detach}
     * methods of any children or {@link DetachListener}s that would be
     * communicated to the client are silently ignored.
     */
    @Override
    public void detach() {
        super.detach();
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * com.vaadin.ui.AbstractSingleComponentContainer#setContent(com.vaadin.
     * ui.Component)
     */
    @Override
    public void setContent(Component content) {
        if (content instanceof Window) {
            throw new IllegalArgumentException(
                    "A Window cannot be added using setContent. Use addWindow(Window window) instead");
        }
        super.setContent(content);
    }

    @Override
    public void setTabIndex(int tabIndex) {
        getState().tabIndex = tabIndex;
    }

    @Override
    public int getTabIndex() {
        return getState(false).tabIndex;
    }

    /**
     * Locks the session of this UI and runs the provided Runnable right away.
     * <p>
     * It is generally recommended to use {@link #access(Runnable)} instead of
     * this method for accessing a session from a different thread as
     * {@link #access(Runnable)} can be used while holding the lock of another
     * session. To avoid causing deadlocks, this methods throws an exception if
     * it is detected than another session is also locked by the current thread.
     * </p>
     * <p>
     * This method behaves differently than {@link #access(Runnable)} in some
     * situations:
     * <ul>
     * <li>If the current thread is currently holding the lock of the session,
     * {@link #accessSynchronously(Runnable)} runs the task right away whereas
     * {@link #access(Runnable)} defers the task to a later point in time.</li>
     * <li>If some other thread is currently holding the lock for the session,
     * {@link #accessSynchronously(Runnable)} blocks while waiting for the lock
     * to be available whereas {@link #access(Runnable)} defers the task to a
     * later point in time.</li>
     * <li>If the session is currently not locked,
     * {@link #accessSynchronously(Runnable)} runs the task right away whereas
     * {@link #access(Runnable)} defers the task to a later point in time unless
     * there are UIs with automatic push enabled.</li>
     * </ul>
     * </p>
     * 
     * @since 7.1
     * 
     * @param runnable
     *            the runnable which accesses the UI
     * @throws UIDetachedException
     *             if the UI is not attached to a session (and locking can
     *             therefore not be done)
     * @throws IllegalStateException
     *             if the current thread holds the lock for another session
     * 
     * @see #access(Runnable)
     * @see VaadinSession#accessSynchronously(Runnable)
     */
    public void accessSynchronously(Runnable runnable)
            throws UIDetachedException {
        Map<Class<?>, CurrentInstance> old = null;

        VaadinSession session = getSession();

        if (session == null) {
            throw new UIDetachedException();
        }

        VaadinService.verifyNoOtherSessionLocked(session);

        session.lock();
        try {
            if (getSession() == null) {
                // UI was detached after fetching the session but before we
                // acquired the lock.
                throw new UIDetachedException();
            }
            old = CurrentInstance.setCurrent(this);
            runnable.run();
        } finally {
            session.unlock();
            if (old != null) {
                CurrentInstance.restoreInstances(old);
            }
        }

    }

    /**
     * Provides exclusive access to this UI from outside a request handling
     * thread.
     * <p>
     * The given runnable is executed while holding the session lock to ensure
     * exclusive access to this UI. If the session is not locked, the lock will
     * be acquired and the runnable is run right away. If the session is
     * currently locked, the runnable will be run before that lock is released.
     * </p>
     * <p>
     * RPC handlers for components inside this UI do not need to use this method
     * as the session is automatically locked by the framework during RPC
     * handling.
     * </p>
     * <p>
     * Please note that the runnable might be invoked on a different thread or
     * later on the current thread, which means that custom thread locals might
     * not have the expected values when the runnable is executed. Inheritable
     * values in {@link CurrentInstance} will have the same values as when this
     * method was invoked. {@link UI#getCurrent()},
     * {@link VaadinSession#getCurrent()} and {@link VaadinService#getCurrent()}
     * are set according to this UI before executing the runnable.
     * Non-inheritable CurrentInstance values including
     * {@link VaadinService#getCurrentRequest()} and
     * {@link VaadinService#getCurrentResponse()} will not be defined.
     * </p>
     * <p>
     * The returned future can be used to check for task completion and to
     * cancel the task.
     * </p>
     * 
     * @see #getCurrent()
     * @see #accessSynchronously(Runnable)
     * @see VaadinSession#access(Runnable)
     * @see VaadinSession#lock()
     * 
     * @since 7.1
     * 
     * @param runnable
     *            the runnable which accesses the UI
     * @throws UIDetachedException
     *             if the UI is not attached to a session (and locking can
     *             therefore not be done)
     * @return a future that can be used to check for task completion and to
     *         cancel the task
     */
    public Future<Void> access(final Runnable runnable) {
        VaadinSession session = getSession();

        if (session == null) {
            throw new UIDetachedException();
        }

        return session.access(new Runnable() {
            @Override
            public void run() {
                accessSynchronously(runnable);
            }
        });
    }

    /**
     * @deprecated As of 7.1.0.beta1, use {@link #accessSynchronously(Runnable)}
     *             or {@link #access(Runnable)} instead. This method will be
     *             removed before the final 7.1.0 release.
     */
    @Deprecated
    public void runSafely(Runnable runnable) throws UIDetachedException {
        accessSynchronously(runnable);
    }

    /**
     * Retrieves the object used for configuring tooltips.
     * 
     * @return The instance used for tooltip configuration
     */
    public TooltipConfiguration getTooltipConfiguration() {
        return tooltipConfiguration;
    }

    /**
     * Retrieves the object used for configuring the loading indicator.
     * 
     * @return The instance used for configuring the loading indicator
     */
    public LoadingIndicatorConfiguration getLoadingIndicatorConfiguration() {
        return loadingIndicatorConfiguration;
    }

    /**
     * Pushes the pending changes and client RPC invocations of this UI to the
     * client-side.
     * <p>
     * As with all UI methods, it is not safe to call push() without holding the
     * {@link VaadinSession#lock() session lock}.
     * 
     * @throws IllegalStateException
     *             if push is disabled.
     * @throws UIDetachedException
     *             if this UI is not attached to a session.
     * 
     * @see #getPushMode()
     * 
     * @since 7.1
     */
    public void push() {
        VaadinSession session = getSession();
        if (session != null) {
            assert session.hasLock();

            /*
             * Purge the pending access queue as it might mark a connector as
             * dirty when the push would otherwise be ignored because there are
             * no changes to push.
             */
            session.runPendingAccessTasks();

            if (!getConnectorTracker().hasDirtyConnectors()) {
                // Do not push if there is nothing to push
                return;
            }

            if (!getPushMode().isEnabled()) {
                throw new IllegalStateException("Push not enabled");
            }

            if (pushConnection == null) {
                hasPendingPush = true;
            } else {
                pushConnection.push();
            }
        } else {
            throw new UIDetachedException("Trying to push a detached UI");
        }
    }

    /**
     * Returns the internal push connection object used by this UI. This method
     * should only be called by the framework.
     */
    public PushConnection getPushConnection() {
        return pushConnection;
    }

    /**
     * Sets the internal push connection object used by this UI. This method
     * should only be called by the framework.
     */
    public void setPushConnection(PushConnection pushConnection) {
        // If pushMode is disabled then there should never be a pushConnection
        assert (getPushMode().isEnabled() || pushConnection == null);

        if (pushConnection == this.pushConnection) {
            return;
        }

        if (this.pushConnection != null) {
            this.pushConnection.disconnect();
        }

        this.pushConnection = pushConnection;
        if (pushConnection != null && hasPendingPush) {
            hasPendingPush = false;
            pushConnection.push();
        }
    }

    /**
     * Sets the interval with which the UI should poll the server to see if
     * there are any changes. Polling is disabled by default.
     * <p>
     * Note that it is possible to enable push and polling at the same time but
     * it should not be done to avoid excessive server traffic.
     * </p>
     * <p>
     * Add-on developers should note that this method is only meant for the
     * application developer. An add-on should not set the poll interval
     * directly, rather instruct the user to set it.
     * </p>
     * 
     * @param intervalInMillis
     *            The interval (in ms) with which the UI should poll the server
     *            or -1 to disable polling
     */
    public void setPollInterval(int intervalInMillis) {
        getState().pollInterval = intervalInMillis;
    }

    /**
     * Returns the interval with which the UI polls the server.
     * 
     * @return The interval (in ms) with which the UI polls the server or -1 if
     *         polling is disabled
     */
    public int getPollInterval() {
        return getState(false).pollInterval;
    }

    /**
     * Returns the mode of bidirectional ("push") communication that is used in
     * this UI.
     * 
     * @return The push mode.
     */
    public PushMode getPushMode() {
        return getState(false).pushMode;
    }

    /**
     * Sets the mode of bidirectional ("push") communication that should be used
     * in this UI.
     * <p>
     * Add-on developers should note that this method is only meant for the
     * application developer. An add-on should not set the push mode directly,
     * rather instruct the user to set it.
     * </p>
     * 
     * @param pushMode
     *            The push mode to use.
     * 
     * @throws IllegalArgumentException
     *             if the argument is null.
     * @throws IllegalStateException
     *             if push support is not available.
     */
    public void setPushMode(PushMode pushMode) {
        if (pushMode == null) {
            throw new IllegalArgumentException("Push mode cannot be null");
        }

        if (pushMode.isEnabled()) {
            VaadinSession session = getSession();
            if (session != null && !session.getService().ensurePushAvailable()) {
                throw new IllegalStateException(
                        "Push is not available. See previous log messages for more information.");
            }
        }

        /*
         * Client-side will open a new connection or disconnect the old
         * connection, so there's nothing more to do on the server at this
         * point.
         */
        getState().pushMode = pushMode;
    }

    /**
     * Get the label that is added to the container element, where tooltip,
     * notification and dialogs are added to.
     * 
     * @return the label of the container
     */
    public String getOverlayContainerLabel() {
        return getState().overlayContainerLabel;
    }

    /**
     * Sets the label that is added to the container element, where tooltip,
     * notifications and dialogs are added to.
     * <p>
     * This is helpful for users of assistive devices, as this element is
     * reachable for them.
     * </p>
     * 
     * @param overlayContainerLabel
     *            label to use for the container
     */
    public void setOverlayContainerLabel(String overlayContainerLabel) {
        getState().overlayContainerLabel = overlayContainerLabel;
    }
}
