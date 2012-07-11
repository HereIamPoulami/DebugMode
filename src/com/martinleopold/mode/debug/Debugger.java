/*
 * Copyright (C) 2012 Martin Leopold <m@martinleopold.com>
 *
 * This program is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation; either version 2 of the License, or (at your option) any later
 * version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program; if not, write to the Free Software Foundation, Inc., 59 Temple
 * Place - Suite 330, Boston, MA 02111-1307, USA.
 */
package com.martinleopold.mode.debug;

import com.sun.jdi.*;
import com.sun.jdi.event.*;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequestManager;
import com.sun.jdi.request.StepRequest;
import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import processing.app.Sketch;
import processing.app.SketchCode;

/**
 * Main controller class for debugging mode. Mainly works with DebugEditor as
 * the corresponding "view". Uses DebugRunner to launch a VM.
 *
 * @author Martin Leopold <m@martinleopold.com>
 */
public class Debugger implements VMEventListener {

    protected DebugEditor editor; // editor window, acting as main view
    protected DebugRunner runtime; // the runtime, contains debuggee VM
    protected boolean started = false; // debuggee vm has started, VMStartEvent received, main class loaded
    protected boolean paused = false; // currently paused at breakpoint or step
    //ThreadReference initialThread; // initial thread of debuggee vm
    protected ThreadReference currentThread; // thread the last breakpoint or step occured in
    protected String mainClassName; // name of the main class that's currently being debugged
    protected ReferenceType mainClass;
    protected Set<ReferenceType> classes = new HashSet();
    protected String srcPath; // path to the src folder of the current build
    protected DebugBuild build; // todo: might not need to be global
    //protected Map<LineID, LineID> lineMap = new HashMap(); // maps source lines from "sketch-space" to "java-space" and vice-versa
    protected List<LineBreakpoint> breakpoints = new ArrayList();
    protected StepRequest requestedStep; // the step request we are currently in, or null if not in a step
    protected Map<LineID, LineID> runtimeLineChanges = new HashMap(); // maps line number changes at runtime (orig -> changed)
    protected Set<String> runtimeTabsTracked = new HashSet(); // contains tab filenames which already have been tracked for runtime changes

    /**
     * Construct a Debugger object.
     *
     * @param editor The Editor that will act as primary view
     */
    public Debugger(DebugEditor editor) {
        this.editor = editor;
    }

    /**
     * Access the VM.
     *
     * @return the virtual machine object or null if not available.
     */
    public VirtualMachine vm() {
        if (runtime != null) {
            return runtime.vm();
        } else {
            return null;
        }
    }

    // TODO: fix this
    public ReferenceType mainClass() {
        return mainClass;
    }

    public ReferenceType getClass(String name) {
        if (name == null) {
            return null;
        }
        if (name.equals(mainClassName)) {
            return mainClass;
        }
        for (ReferenceType rt : classes) {
            if (rt.name().equals(name)) {
                return rt;
            }
        }
        return null;
    }
    protected List<ClassLoadListener> classLoadListeners = new ArrayList();

    public void addClassLoadListener(ClassLoadListener listener) {
        classLoadListeners.add(listener);
    }

    public void removeClassLoadListener(ClassLoadListener listener) {
        classLoadListeners.remove(listener);
    }

//    public Map<LineID, LineID> lineMapping() {
//        return lineMap;
//    }
    /**
     * Access the editor associated with this debugger.
     *
     * @return the editor object
     */
    public DebugEditor editor() {
        return editor;
    }

    /**
     * Start a debugging session. Builds the sketch and launches a VM to run it.
     * VM starts suspended. Should produce a VMStartEvent.
     */
    public synchronized void startDebug() {
        stopDebug(); // stop any running sessions

        // clear console
        editor.clearConsole();

        // load edits into sketch obj, etc...
        editor.prepareRun();

        try {
            Sketch sketch = editor.getSketch();
            build = new DebugBuild(sketch);

            Logger.getLogger(Debugger.class.getName()).log(Level.INFO, "building sketch: {0}", sketch.getName());
            //LineMapping.addLineNumbers(sketch); // annotate
            mainClassName = build.build(false);
            //LineMapping.removeLineNumbers(sketch); // annotate
            Logger.getLogger(Debugger.class.getName()).log(Level.INFO, "class: {0}", mainClassName);

            // folder with assembled/preprocessed src
            srcPath = build.getSrcFolder().getPath();
            Logger.getLogger(Debugger.class.getName()).log(Level.INFO, "build src: {0}", srcPath);
            // folder with compiled code (.class files)
            Logger.getLogger(Debugger.class.getName()).log(Level.INFO, "build bin: {0}", build.getBinFolder().getPath());

            if (mainClassName != null) {
                // generate the source line mapping
                //lineMap = LineMapping.generateMapping(srcPath + File.separator + mainClassName + ".java");

                Logger.getLogger(Debugger.class.getName()).log(Level.INFO, "launching debuggee runtime");
                runtime = new DebugRunner(build, editor);
                VirtualMachine vm = runtime.launch(); // non-blocking
                if (vm == null) {
                    Logger.getLogger(Debugger.class.getName()).log(Level.SEVERE, "error 37: launch failed");
                }

                // start receiving vm events
                VMEventReader eventThread = new VMEventReader(vm.eventQueue(), this);
                eventThread.start();

                //return runtime;

                /*
                 * // launch runner in new thread new Thread(new Runnable() {
                 *
                 * @Override public void run() { runtime.launch(false); // this
                 * blocks until finished } }).start(); return runtime;
                 */


                // test setting a breakpoint on setup()
                //Location
                //vm.eventRequestManager().createBreakpointRequest(null);

                startTrackingLineChanges();
            }
        } catch (Exception e) {
            editor.statusError(e);
        }

        //return null;
    }

    /**
     * End debugging session. Stops and disconnects VM. Should produce
     * VMDisconnectEvent.
     */
    public synchronized void stopDebug() {
        if (runtime != null) {
            Logger.getLogger(Debugger.class.getName()).log(Level.INFO, "closing runtime");
            runtime.close();
            runtime = null;
            build = null;
            classes.clear();
            // need to clear highlight here because, VMDisconnectedEvent seems to be unreliable. TODO: likely synchronization problem
            //clearHighlight();
            editor.clearCurrentLine();
        }
        stopTrackingLineChanges();
        started = false;
        editor.variableInspector().lock();
    }

    /**
     * Resume paused debugging session. Resumes VM.
     */
    public synchronized void continueDebug() {
        //editor.clearSelection();
        //clearHighlight();
        editor.clearCurrentLine();
        editor.variableInspector().lock();
        if (!isStarted()) {
            startDebug();
        } else if (isPaused()) {
            runtime.vm().resume();
            paused = false;
        }
    }

    /**
     * Step through source code lines.
     *
     * @param stepDepth the step depth ({@link StepRequest}{@code .STEP_OVER},
     * {@link StepRequest}{@code .STEP_INTO} or
     * {@link StepRequest}{@code .STEP_OUT})
     */
    protected void step(int stepDepth) {
        if (isPaused()) {
            // use global to mark that there is a step request pending
            requestedStep = runtime.vm().eventRequestManager().createStepRequest(currentThread, StepRequest.STEP_LINE, stepDepth);
            requestedStep.addCountFilter(1); // valid for one step only
            requestedStep.enable();
            paused = false;
            runtime.vm().resume();
            editor.variableInspector().lock();
        }
    }

    /**
     * Step over current statement.
     */
    public synchronized void stepOver() {
        step(StepRequest.STEP_OVER);
    }

    /**
     * Step into current statement.
     */
    public synchronized void stepInto() {
        step(StepRequest.STEP_INTO);
    }

    /**
     * Step out of current function.
     */
    public synchronized void stepOut() {
        step(StepRequest.STEP_OUT);
    }

    /**
     * Print the current stack trace.
     */
    public synchronized void printStackTrace() {
        if (isStarted()) {
            printStackTrace(currentThread);
        }
    }

    /**
     * Print local variables. Outputs type, name and value of each variable.
     */
    public synchronized void printLocals() {
        if (isStarted()) {
            printLocalVariables(currentThread);
        }
    }

    /**
     * Print fields of current {@code this}-object. Outputs type, name and value
     * of each field.
     */
    public synchronized void printThis() {
        if (isStarted()) {
            printThis(currentThread);
        }
    }

    /**
     * Print a source code snippet of the current location.
     */
    public synchronized void printSource() {
        if (isStarted()) {
            printSourceLocation(currentThread);
        }
    }

    /**
     * Set a breakpoint on the current line.
     */
    public synchronized void setBreakpoint() {
        // do nothing if we are kinda busy
        if (isStarted() && !isPaused()) {
            return;
        }
        LineID line = editor.getCurrentLineID();
        breakpoints.add(new LineBreakpoint(line.lineIdx, this));
        Logger.getLogger(Debugger.class.getName()).log(Level.INFO, "set breakpoint on line {0}", line);
        //System.out.println("note: breakpoints on method declarations will not work, use first line of method instead");
    }

    /**
     * Remove a breakpoint from the current line (if set).
     */
    public synchronized void removeBreakpoint() {
        // do nothing if we are kinda busy
        if (isBusy()) {
            return;
        }

        LineBreakpoint bp = breakpointOnLine(editor.getCurrentLineID());
        if (bp != null) {
            bp.remove();
            breakpoints.remove(bp);
            Logger.getLogger(Debugger.class.getName()).log(Level.INFO, "removed breakpoint {0}", bp);
        }
    }

    /**
     * Remove all breakpoints.
     */
    public synchronized void clearBreakpoints() {
        //TODO: handle busy-ness correctly
        if (isBusy()) {
            Logger.getLogger(Debugger.class.getName()).log(Level.WARNING, "busy");
            return;
        }

        for (LineBreakpoint bp : breakpoints) {
            bp.remove();
        }
        breakpoints.clear();
    }

    /**
     * Get the breakpoint on a certain line, if set.
     *
     * @param line the line to get the breakpoint from
     * @return the breakpoint, or null if no breakpoint is set on the specified
     * line.
     */
    protected LineBreakpoint breakpointOnLine(LineID line) {
        for (LineBreakpoint bp : breakpoints) {
            if (bp.isOnLine(line)) {
                return bp;
            }
        }
        return null;
    }

    /**
     * Toggle the breakpoint on the current line.
     */
    public synchronized void toggleBreakpoint() {
        LineID line = editor.getCurrentLineID();

        LineBreakpoint bp = breakpointOnLine(line);
        if (bp == null) {
            setBreakpoint();
        } else {
            removeBreakpoint();
        }
    }

    /**
     * Print a list of currently set breakpoints.
     */
    public synchronized void listBreakpoints() {
        if (breakpoints.isEmpty()) {
            System.out.println("no breakpoints");
        } else {
            System.out.println("line breakpoints:");
            for (LineBreakpoint bp : breakpoints) {
                System.out.println(bp);
            }
        }
    }

    /**
     * Callback for VM events. Will be called from another thread.
     * ({@link VMEventReader})
     *
     * @param es Incoming set of events from VM
     */
    @Override
    public synchronized void vmEvent(EventSet es) {
        for (Event e : es) {
            Logger.getLogger(Debugger.class.getName()).log(Level.INFO, "*** VM Event: {0}", e.toString());
            if (e instanceof VMStartEvent) {
                //initialThread = ((VMStartEvent) e).thread();
                ThreadReference t = ((VMStartEvent) e).thread();
                //printStackTrace(t);

                // break on main class load
                Logger.getLogger(Debugger.class.getName()).log(Level.INFO, "requesting event on main class load: {0}", mainClassName);
                ClassPrepareRequest mainClassPrepare = runtime.vm().eventRequestManager().createClassPrepareRequest();
                mainClassPrepare.addClassFilter(mainClassName);
                mainClassPrepare.enable();

                // break on loading custom classes
                for (SketchCode tab : editor.getSketch().getCode()) {
                    if (tab.isExtension("java")) {
                        Logger.getLogger(Debugger.class.getName()).log(Level.INFO, "requesting event on class load: {0}", tab.getPrettyName());
                        ClassPrepareRequest customClassPrepare = runtime.vm().eventRequestManager().createClassPrepareRequest();
                        customClassPrepare.addClassFilter(tab.getPrettyName());
                        customClassPrepare.enable();
                    }

                }

                runtime.vm().resume();
            } else if (e instanceof ClassPrepareEvent) {
                ClassPrepareEvent ce = (ClassPrepareEvent) e;
                ReferenceType rt = ce.referenceType();
                currentThread = ce.thread();
                paused = true; // for now we're paused

                if (rt.name().equals(mainClassName)) {
                    //printType(rt);
                    mainClass = rt;
                    Logger.getLogger(Debugger.class.getName()).log(Level.INFO, "main class load: {0}", rt.name());

                    //                if (breakpoints.isEmpty()) {
                    //                    System.out.println("using auto brekapoints (no manual breakpoints set)");
                    //                    System.out.println("setting breakpoint on setup()");
                    //                    Location setupLocation = rt.methodsByName("setup").get(0).location();
                    //                    BreakpointRequest setupBp =
                    //                            runtime.vm().eventRequestManager().createBreakpointRequest(setupLocation);
                    //                    setupBp.enable();
                    //
                    //                    System.out.println("setting breakpoint on draw()");
                    //                    Location drawLocation = rt.methodsByName("draw").get(0).location();
                    //                    BreakpointRequest drawBp =
                    //                            runtime.vm().eventRequestManager().createBreakpointRequest(drawLocation);
                    //                    drawBp.enable();
                    //                } else {
                    //                    System.out.println("setting breakpoints:");
                    //                    for (LineBreakpoint bp : breakpoints) {
                    //                        bp.attach();
                    //                    }
                    //                }

//                    if (!breakpoints.isEmpty()) {
//                        Logger.getLogger(Debugger.class.getName()).log(Level.INFO, "setting breakpoints:");
//                        for (LineBreakpoint bp : breakpoints) {
//                            bp.attach();
//                        }
//                    }
                    started = true; // now that main class is loaded, we're started
                } else {
                    classes.add(rt);
                    Logger.getLogger(Debugger.class.getName()).log(Level.INFO, "class load: {0}", rt.name());
                }

                // notify listeners
                for (ClassLoadListener listener : classLoadListeners) {
                    if (listener != null) {
                        listener.classLoaded(rt);
                    }
                }

                paused = false; // resuming now
                runtime.vm().resume();
            } else if (e instanceof BreakpointEvent) {
                BreakpointEvent be = (BreakpointEvent) e;
                currentThread = be.thread(); // save this thread
                BreakpointRequest br = (BreakpointRequest) be.request();

                //printSourceLocation(currentThread);
                editor.setCurrentLine(locationToLineID(be.location()));

                updateVariableInspector(currentThread);

                // hit a breakpoint during a step, need to cancel the step.
                if (requestedStep != null) {
                    runtime.vm().eventRequestManager().deleteEventRequest(requestedStep);
                    requestedStep = null;
                }

                // fix canvas update issue
                // TODO: is this a good solution?
                resumeOtherThreads(currentThread);

                paused = true;
            } else if (e instanceof StepEvent) {
                StepEvent se = (StepEvent) e;
                currentThread = se.thread();

                //printSourceLocation(currentThread);
                editor.setCurrentLine(locationToLineID(se.location()));
                updateVariableInspector(currentThread);

                // delete the steprequest that triggered this step so new ones can be placed (only one per thread)
                EventRequestManager mgr = runtime.vm().eventRequestManager();
                mgr.deleteEventRequest(se.request());
                requestedStep = null; // mark that there is no step request pending
                paused = true;

                // disallow stepping into invisible lines
                if (!locationIsVisible(se.location())) {
                    stepIntoView(currentThread);
                }
            } else if (e instanceof VMDisconnectEvent) {
//                started = false;
//                // clear line highlight
//                editor.clearCurrentLine();
                stopDebug();
            } else if (e instanceof VMDeathEvent) {
                started = false;
            }
        }
    }

    protected boolean locationIsVisible(Location l) {
        return locationToLineID(l) != null;
    }

    // step out to the next visible location on the stackframe or continue
    protected void stepIntoView(ThreadReference thread) {
        try {
            List<StackFrame> frames = thread.frames();
            if (frames.size() > 1) {
                if (locationIsVisible(frames.get(1).location())) {
                    System.out.println("stepping out to: " + locationToString(frames.get(1).location()));
                    stepOut();
                    return;
                }
            }
            continueDebug();

//            if (thread.frames(i, i1))
//            for (StackFrame f : thread.frames()) {
//                    Location l = f.location();
//                    if (locationIsVisible(l)) {
//                        System.out.println("need to step out to: " + locationToString(l));
//                    }
//                }
        } catch (IncompatibleThreadStateException ex) {
            Logger.getLogger(Debugger.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    /**
     * Check whether a debugging session is running. i.e. the debugger is
     * connected to a debuggee VM, VMStartEvent has been received and main class
     * is loaded.
     *
     * @return true if the debugger is started.
     */
    public synchronized boolean isStarted() {
        return started && runtime != null && runtime.vm() != null;
    }

    /**
     * Check whether the debugger is paused. i.e. it is currently suspended at a
     * breakpoint or step
     *
     * @return true if the debugger is paused, false otherwise or if not started
     * ({@link #isStarted()})
     */
    public synchronized boolean isPaused() {
        return isStarted() && paused && currentThread != null && currentThread.isSuspended();
    }

    public synchronized boolean isBusy() {
        return isStarted() && !isPaused();
    }

    /**
     * Print call stack trace of a thread. Only works on suspended threads.
     *
     * @param t suspended thread to print stack trace of
     */
    protected void printStackTrace(ThreadReference t) {
        if (!t.isSuspended()) {
            return;
        }
        try {
            System.out.println("stack trace for thread " + t.name() + ":");
            int i = 0;
            for (StackFrame f : t.frames()) {
                Location l = f.location();
                System.out.println(i++ + ": " + f.toString());
            }
        } catch (IncompatibleThreadStateException ex) {
            Logger.getLogger(Debugger.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    /**
     * Resume all other threads except the one given as parameter. Useful e.g.
     * to just keep the thread suspended a breakpoint occurred in.
     *
     * @param t the thread not to resume
     */
    protected void resumeOtherThreads(ThreadReference t) {
        if (!isStarted()) {
            return;
        }
        for (ThreadReference other : vm().allThreads()) {
            if (!other.equals(t) && other.isSuspended()) {
                other.resume();
            }
        }
    }

    /**
     * Print info about all current threads. Includes name, status, isSuspended,
     * isAtBreakpoint.
     */
    public synchronized void printThreads() {
        if (!isPaused()) {
            return;
        }
        System.out.println("threads:");
        for (ThreadReference t : vm().allThreads()) {
            printThread(t);
        }
    }

    /**
     * Print info about a thread. Includes name, status, isSuspended,
     * isAtBreakpoint.
     *
     * @param t the thread to print info about
     */
    protected void printThread(ThreadReference t) {
        System.out.println(t.name());
        System.out.println("   is suspended: " + t.isSuspended());
        System.out.println("   is at breakpoint: " + t.isAtBreakpoint());
        System.out.println("   status: " + threadStatusToString(t.status()));
    }

    /**
     * Convert a status code returned by {@link ThreadReference#status() } to a
     * human readable form.
     *
     * @param status {@link ThreadReference#THREAD_STATUS_MONITOR},
     * {@link ThreadReference#THREAD_STATUS_NOT_STARTED},
     * {@link ThreadReference#THREAD_STATUS_RUNNING},
     * {@link ThreadReference#THREAD_STATUS_SLEEPING},
     * {@link ThreadReference#THREAD_STATUS_UNKNOWN},
     * {@link ThreadReference#THREAD_STATUS_WAIT} or
     * {@link ThreadReference#THREAD_STATUS_ZOMBIE}
     * @return String containing readable status code.
     */
    protected String threadStatusToString(int status) {
        switch (status) {
            case ThreadReference.THREAD_STATUS_MONITOR:
                return "THREAD_STATUS_MONITOR";
            case ThreadReference.THREAD_STATUS_NOT_STARTED:
                return "THREAD_STATUS_NOT_STARTED";
            case ThreadReference.THREAD_STATUS_RUNNING:
                return "THREAD_STATUS_RUNNING";
            case ThreadReference.THREAD_STATUS_SLEEPING:
                return "THREAD_STATUS_SLEEPING";
            case ThreadReference.THREAD_STATUS_UNKNOWN:
                return "THREAD_STATUS_UNKNOWN";
            case ThreadReference.THREAD_STATUS_WAIT:
                return "THREAD_STATUS_WAIT";
            case ThreadReference.THREAD_STATUS_ZOMBIE:
                return "THREAD_STATUS_ZOMBIE";
            default:
                return "";
        }
    }

    /**
     * Print local variables on a suspended thread. Takes the topmost stack
     * frame and lists all local variables and their values.
     *
     * @param t suspended thread
     */
    protected void printLocalVariables(ThreadReference t) {
        if (!t.isSuspended()) {
            return;
        }
        try {
            if (t.frameCount() == 0) {
                System.out.println("call stack empty");
            } else {
                StackFrame sf = t.frame(0);
                List<LocalVariable> locals = sf.visibleVariables();
                if (locals.isEmpty()) {
                    System.out.println("no local variables");
                    return;
                }
                for (LocalVariable lv : locals) {
                    System.out.println(lv.typeName() + " " + lv.name() + " = " + sf.getValue(lv));
                }
            }
        } catch (IncompatibleThreadStateException ex) {
            Logger.getLogger(Debugger.class.getName()).log(Level.SEVERE, null, ex);
        } catch (AbsentInformationException ex) {
            System.out.println("local variable information not available");
        }
    }

    /**
     * Update variable inspector window. Displays local variables and this
     * fields.
     *
     * @param t suspended thread to retrieve locals and this
     */
    protected void updateVariableInspector(ThreadReference t) {
        if (!t.isSuspended()) {
            return;
        }
        VariableInspector vi = editor.variableInspector();
        try {
            if (t.frameCount() == 0) {
                // TODO: needs to be handled in a better way:
                Logger.getLogger(Debugger.class.getName()).log(Level.WARNING, "call stack empty");
            } else {
                vi.updateCallStack(getStackTrace(t), "Call Stack");
                List<VariableNode> locals = getLocals(t, 0);
                vi.updateLocals(locals, "Locals at " + currentLocation(t));
                vi.updateThisFields(getThisFields(t, 0, true), "Class " + thisName(t));
                vi.updateDeclaredThisFields(getThisFields(t, 0, false), "Class " + thisName(t));
                vi.unlock(); // need to do this before rebuilding, otherwise we get these ... dots in the labels
                vi.rebuild();
            }
        } catch (IncompatibleThreadStateException ex) {
            Logger.getLogger(Debugger.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    /**
     * Get the class name of the current this object in a suspended thread.
     *
     * @param t a suspended thread
     * @return the class name of this
     */
    protected String thisName(ThreadReference t) {
        try {
            if (!t.isSuspended() || t.frameCount() == 0) {
                return "";
            }
            return t.frame(0).thisObject().referenceType().name();
        } catch (IncompatibleThreadStateException ex) {
            Logger.getLogger(Debugger.class.getName()).log(Level.SEVERE, null, ex);
            return "";
        }
    }

    /**
     * Get a description of the current location in a suspended thread. Format:
     * class.method:translated_line_number
     *
     * @param t a suspended thread
     * @return descriptive string for the given location
     */
    protected String currentLocation(ThreadReference t) {
        try {
            if (!t.isSuspended() || t.frameCount() == 0) {
                return "";
            }
            return locationToString(t.frame(0).location());
        } catch (IncompatibleThreadStateException ex) {
            Logger.getLogger(Debugger.class.getName()).log(Level.SEVERE, null, ex);
            return "";
        }
    }

    /**
     * Get a string describing a location. Format:
     * class.method:translated_line_number
     *
     * @param l a location
     * @return descriptive string for the given location
     */
    protected String locationToString(Location l) {
        LineID line = locationToLineID(l);
        int lineNumber;
        if (line != null) {
            lineNumber = line.lineIdx() + 1;
        } else {
            lineNumber = l.lineNumber();
        }
        return l.declaringType().name() + "." + l.method().name() + ":" + lineNumber;
    }

    /**
     * Compile a list of current locals usable for insertion into a
     * {@link JTree}. Recursively resolves object references.
     *
     * @param t the suspended thread to get locals for
     * @param depth how deep to resolve nested object references. 0 will not
     * resolve nested objects.
     * @return the list of current locals
     */
    protected List<VariableNode> getLocals(ThreadReference t, int depth) {
        //System.out.println("getting locals");
        List<VariableNode> vars = new ArrayList();
        try {
            if (t.frameCount() > 0) {
                StackFrame sf = t.frame(0);
                for (LocalVariable lv : sf.visibleVariables()) {
                    //System.out.println("local var: " + lv.name());
                    Value val = sf.getValue(lv);
                    VariableNode var = new VariableNode(lv.name(), lv.typeName(), val);
                    if (depth > 0) {
                        var.addChildren(getFields(val, depth - 1, true));
                    }
                    vars.add(var);
                }
            }
        } catch (IncompatibleThreadStateException ex) {
            Logger.getLogger(Debugger.class.getName()).log(Level.SEVERE, null, ex);
        } catch (AbsentInformationException ex) {
            Logger.getLogger(Debugger.class.getName()).log(Level.WARNING, "local variable information not available", ex);
        }
        return vars;
    }

    /**
     * Compile a list of fields in the current this object usable for insertion
     * into a {@link JTree}. Recursively resolves object references.
     *
     * @param t the suspended thread to get locals for
     * @param depth how deep to resolve nested object references. 0 will not
     * resolve nested objects.
     * @return the list of fields in the current this object
     */
    protected List<VariableNode> getThisFields(ThreadReference t, int depth, boolean includeInherited) {
        //System.out.println("getting this");
        try {
            if (t.frameCount() > 0) {
                StackFrame sf = t.frame(0);
                ObjectReference thisObj = sf.thisObject();
                return getFields(thisObj, depth, includeInherited);
            }
        } catch (IncompatibleThreadStateException ex) {
            Logger.getLogger(Debugger.class.getName()).log(Level.SEVERE, null, ex);
        }
        return new ArrayList();
    }

//    /**
//     * Recursively resolve a field for use in a {@link JTree}. Uses an object
//     * reference as environment. Used by {@link #getLocals} and
//     * {@link #getThisFields}.
//     *
//     * @param field the field to resolve
//     * @param obj the object reference used as environment (must contain the
//     * field to resolve)
//     * @param depth the current depth (in the recursive call)
//     * @param maxDepth the depth to stop recursion at
//     * @return the resolved field
//     */
//    protected VariableNode getFieldRecursive(Field field, ObjectReference obj, int depth, int maxDepth) {
//        // resolve the field to a value using the provided object reference
//        Value val = obj.getValue(field);
//        VariableNode var = new VariableNode(field.name(), field.typeName(), val == null ? "null" : val.toString());
//        //System.out.println("field: " + var);
//
//        if (val != null && depth < maxDepth) {
//            // add all child fields (if field represents an object)
//            if (val instanceof ObjectReference) {
//                ObjectReference env = (ObjectReference) val;
//                //add all children
//                for (Field f : env.referenceType().visibleFields()) {
//                    //System.out.print(String.format(String.format("%%0%dd", depth + 1), 0).replace("0", "  "));
//                    var.addChild(getFieldRecursive(f, env, depth + 1, maxDepth));
//                }
//            }
//        }
//        return var;
//    }
    /**
     * Recursively get the fields of a {@link Value} for insertion into a
     * {@link JTree}.
     *
     * @param value must be an instance of {@link ObjectReference}
     * @param depth the current depth
     * @param maxDepth the depth to stop at (inclusive)
     * @return list of child fields of the given value
     */
    protected List<VariableNode> getFields(Value value, int depth, int maxDepth, boolean includeInherited) {
        // remember: Value <- ObjectReference, ArrayReference
        List<VariableNode> vars = new ArrayList();
        if (depth <= maxDepth) {
            if (value instanceof ArrayReference) {
                return getArrayFields((ArrayReference) value);
            } else if (value instanceof ObjectReference) {
                ObjectReference obj = (ObjectReference) value;
                // get the fields of this object
                List<Field> fields = includeInherited ? obj.referenceType().visibleFields() : obj.referenceType().fields();
                for (Field field : fields) {
                    Value val = obj.getValue(field); // get the value, may be null
                    VariableNode var = new VariableNode(field.name(), field.typeName(), val);
                    // recursively add children
                    if (val != null) {
                        var.addChildren(getFields(val, depth + 1, maxDepth, includeInherited));
                    }
                    vars.add(var);
                }
            }
        }
        return vars;
    }

    /**
     * Recursively get the fields of a {@link Value} for insertion into a
     * {@link JTree}.
     *
     * @param value must be an instance of {@link ObjectReference}
     * @param maxDepth max recursion depth. 0 will give only direct children
     * @return list of child fields of the given value
     */
    protected List<VariableNode> getFields(Value value, int maxDepth, boolean includeInherited) {
        return getFields(value, 0, maxDepth, includeInherited);
    }

    // TODO: doc
    protected List<VariableNode> getArrayFields(ArrayReference array) {
        List<VariableNode> fields = new ArrayList();
        if (array != null) {
            String arrayType = array.type().name();
            if (arrayType.endsWith("[]")) {
                arrayType = arrayType.substring(0, arrayType.length() - 2);
            }
            int i = 0;
            for (Value val : array.getValues()) {
                fields.add(new VariableNode("[" + i + "]", arrayType, val));
                i++;
            }
        }
        return fields;
    }

    /**
     * Get the current call stack trace usable for insertion into a
     * {@link JTree}.
     *
     * @param t the suspended thread to retrieve the call stack from
     * @return call stack as list of {@link DefaultMutableTreeNode}s
     */
    protected List<DefaultMutableTreeNode> getStackTrace(ThreadReference t) {
        List<DefaultMutableTreeNode> stack = new ArrayList();
        try {
            int i = 0;
            for (StackFrame f : t.frames()) {
                stack.add(new DefaultMutableTreeNode(locationToString(f.location())));
            }
        } catch (IncompatibleThreadStateException ex) {
            Logger.getLogger(Debugger.class.getName()).log(Level.SEVERE, null, ex);
        }
        return stack;
    }

    /**
     * Print visible fields of current "this" object on a suspended thread.
     * Prints type, name and value.
     *
     * @param t suspended thread
     */
    protected void printThis(ThreadReference t) {
        if (!t.isSuspended()) {
            return;
        }
        try {
            if (t.frameCount() == 0) {
                // TODO: needs to be handled in a better way
                System.out.println("call stack empty");
            } else {
                StackFrame sf = t.frame(0);
                ObjectReference thisObject = sf.thisObject();
                if (this != null) {
                    ReferenceType type = thisObject.referenceType();
                    System.out.println("fields in this (" + type.name() + "):");
                    for (Field f : type.visibleFields()) {
                        System.out.println(f.typeName() + " " + f.name() + " = " + thisObject.getValue(f));
                    }
                } else {
                    System.out.println("can't get this (in native or static method)");
                }
            }
        } catch (IncompatibleThreadStateException ex) {
            Logger.getLogger(Debugger.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    /**
     * Print source code snippet of current location in a suspended thread.
     *
     * @param t suspended thread
     */
    protected void printSourceLocation(ThreadReference t) {
        try {
            if (t.frameCount() == 0) {
                // TODO: needs to be handled in a better way
                System.out.println("call stack empty");
            } else {
                Location l = t.frame(0).location(); // current stack frame location
                printSourceLocation(l);
            }
        } catch (IncompatibleThreadStateException ex) {
            Logger.getLogger(Debugger.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    /**
     * Print source code snippet.
     *
     * @param l {@link Location} object to print source code for
     */
    protected void printSourceLocation(Location l) {
        try {
            //System.out.println(l.sourceName() + ":" + l.lineNumber());
            System.out.println("in method " + l.method() + ":");
            System.out.println(getSourceLine(l.sourcePath(), l.lineNumber(), 2));

        } catch (AbsentInformationException ex) {
            Logger.getLogger(Debugger.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    /**
     * Read a line from the given file in the builds src folder. 1-based i.e.
     * first line has line no. 1
     *
     * @param filePath
     * @param lineNo
     * @return the requested source line
     */
    protected String getSourceLine(String filePath, int lineNo, int radius) {
        if (lineNo == -1) {
            Logger.getLogger(Debugger.class.getName()).log(Level.SEVERE, "invalid line number: {0}", lineNo);
            return "";
        }
        //System.out.println("getting line: " + lineNo);
        File f = new File(srcPath + File.separator + filePath);
        String output = "";
        try {
            BufferedReader r = new BufferedReader(new FileReader(f));
            int i = 1;
            //String line = "";
            while (i <= lineNo + radius) {
                String line = r.readLine(); // line no. i
                if (line == null) {
                    break; // end of file
                }
                if (i >= lineNo - radius) {
                    if (i > lineNo - radius) {
                        output += "\n"; // add newlines before all lines but the first
                    }
                    output += f.getName() + ":" + i + (i == lineNo ? " =>  " : "     ") + line;
                }
                i++;
            }
            r.close();
            return output;
        } catch (FileNotFoundException ex) {
            //System.err.println(ex);
            return f.getName() + ":" + lineNo;
        } catch (IOException ex) {
            Logger.getLogger(Debugger.class.getName()).log(Level.SEVERE, null, ex);
            return "";
        }
    }

    /**
     * Print info about a ReferenceType. Prints class name, source file name,
     * lists methods.
     *
     * @param rt the reference type to print out
     */
    protected void printType(ReferenceType rt) {
        System.out.println("ref.type: " + rt);
        System.out.println("name: " + rt.name());
        try {
            System.out.println("sourceName: " + rt.sourceName());
        } catch (AbsentInformationException ex) {
            System.out.println("sourceName: unknown");
        }
        System.out.println("methods:");
        for (Method m : rt.methods()) {
            System.out.println(m.toString());
        }
    }

    /**
     * Mark a line in the editor by selecting it. Switches to appropriate tab.
     *
     * @param l {@link Location} object that describes source location to
     * select.
     */
//    protected void selectSourceLocation(Location l) {
//        try {
//            LineID sketchLine = lineMap.get(LineID.create(l.sourceName(), l.lineNumber()));
//            editor.clearSelection();
//            if (sketchLine != null) {
//                int lineIdx = sketchLine.lineIdx(); // 0-based line number
//                String tab = sketchLine.fileName();
//                //System.out.println("sketch line: " + sketchLine);
//
//                // switch to tab
//                Sketch s = editor.getSketch();
//                for (int i = 0; i < s.getCodeCount(); i++) {
//                    if (tab.equals(s.getCode(i).getFileName())) {
//                        s.setCurrentCode(i);
//                        break;
//                    }
//                }
//                // select line
//                editor.selectLine(lineIdx);
//            }
//        } catch (AbsentInformationException ex) {
//            Logger.getLogger(Debugger.class.getName()).log(Level.SEVERE, null, ex);
//        }
//    }
    /**
     * Translate a java source location to a sketch line id.
     *
     * @param l the location to translate
     * @return the corresponding line id, or null if not found
     */
    protected LineID locationToLineID(Location l) {
        try {
            //return lineMap.get(LineID.create(l.sourceName(), l.lineNumber() - 1));
            return javaToSketchLine(new LineID(l.sourceName(), l.lineNumber() - 1));

        } catch (AbsentInformationException ex) {
            Logger.getLogger(Debugger.class.getName()).log(Level.SEVERE, null, ex);
            return null;
        }
    }

    /**
     * Translate a line (index) from java space to sketch space.
     *
     * @param javaLine the java line id
     * @return the corresponding sketch line id or null if failed to translate
     */
    public LineID javaToSketchLine(LineID javaLine) {
        Sketch sketch = editor.getSketch();

        // it may belong to a pure java file created in the sketch
        // try to find an exact filename match and check the extension
        SketchCode tab = editor.getTab(javaLine.fileName());
        if (tab != null && tab.isExtension("java")) {
            // can translate 1:1
            return originalToRuntimeLine(javaLine);
        }

//        for (int i = 0; i < sketch.getCodeCount(); i++) {
//            SketchCode code = sketch.getCode(i);
//            if (code.isExtension("java")) {
//                if (javaLine.fileName().equals(code.getFileName())) {
//                    return LineID.create(code.getFileName(), javaLine.lineIdx());
//                }
//            }
//        }

        // check if it is the preprocessed/assembled file for this sketch
        // java file name needs to match the sketches filename
        if (!javaLine.fileName().equals(sketch.getName() + ".java")) {
            return null;
        }

        // find the tab (.pde file) this line belongs to
        // get the last tab that has an offset not greater than the java line number
        for (int i = sketch.getCodeCount() - 1; i >= 0; i--) {
            tab = sketch.getCode(i);
            // ignore .java files
            // the tabs offset must not be greater than the java line number
            if (tab.isExtension("pde") && tab.getPreprocOffset() <= javaLine.lineIdx()) {
                return originalToRuntimeLine(new LineID(tab.getFileName(), javaLine.lineIdx() - tab.getPreprocOffset()));
            }
        }

        return null;
    }

    /**
     * Get the runtime-changed line id for an original sketch line. Used to
     * translate line numbers from the VM (which runs on the original line
     * numbers) to their current (possibly changed) counterparts.
     *
     * @param line the original line id (at compile time)
     * @return the changed version or the line given as parameter if not found
     */
    protected LineID originalToRuntimeLine(LineID line) {
        LineID transformed = runtimeLineChanges.get(line);
        if (transformed == null) {
            return line;
        }
        return transformed;
    }

    /**
     * Get the original line id for a sketch line that was changed at runtime.
     * Used to translate line numbers from the UI at runtime (which can differ
     * from the ones the VM runs on) to their original counterparts.
     *
     * @param line the (possibly) changed runtime line
     * @return the original line or the line given as parameter if not found
     */
    protected LineID runtimeToOriginalLine(LineID line) {
        for (Entry<LineID, LineID> entry : runtimeLineChanges.entrySet()) {
            if (entry.getValue().equals(line)) {
                return entry.getKey();
            }
        }
        return line;
    }

    /**
     * Translate a line (index) from sketch space to java space.
     *
     * @param sketchLine the sketch line id
     * @return the corresponding java line id or null if failed to translate
     */
    public LineID sketchToJavaLine(LineID sketchLine) {
        sketchLine = runtimeToOriginalLine(sketchLine); // transform back to orig (before changes at runtime)

        // check if there is a tab for this line
        SketchCode tab = editor.getTab(sketchLine.fileName());
        if (tab == null) {
            return null;
        }

        // check if the tab is a pure java file anyway
        if (tab.isExtension("java")) {
            // 1:1 translation
            return sketchLine;
        }

        // the java file has a name sketchname.java
        // just add the tabs offset to get the java name
        LineID javaLine = new LineID(editor.getSketch().getName() + ".java", sketchLine.lineIdx() + tab.getPreprocOffset());
        return javaLine;
    }

    /**
     * Start tracking all line changes (due to edits) in the current tab.
     */
    protected void startTrackingLineChanges() {
        SketchCode tab = editor.getSketch().getCurrentCode();
        if (runtimeTabsTracked.contains(tab.getFileName())) {
            return;
        }

        for (int i = 0; i < tab.getLineCount(); i++) {
            LineID old = new LineID(tab.getFileName(), i);
            LineID tracked = new LineID(tab.getFileName(), i);
            tracked.startTracking(editor.currentDocument());
            runtimeLineChanges.put(old, tracked);
        }
        runtimeTabsTracked.add(tab.getFileName());
        //System.out.println("tracking tab: " + tab.getFileName());
    }

    /**
     * Stop tracking line changes in all tabs.
     */
    protected void stopTrackingLineChanges() {
        //System.out.println("stop tracking line changes");
        for (LineID tracked : runtimeLineChanges.values()) {
            tracked.stopTracking();
        }
        runtimeLineChanges.clear();
        runtimeTabsTracked.clear();
    }
}
