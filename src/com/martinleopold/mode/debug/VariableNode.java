package com.martinleopold.mode.debug;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import javax.swing.tree.MutableTreeNode;
import javax.swing.tree.TreeNode;

/**
 * Model for a variable in the variable inspector. Has a type and name and
 * optionally a value. Can have sub-variables (as is the case for objects, and
 * arrays).
 *
 * @author mlg
 */
public class VariableNode implements MutableTreeNode {

    protected String type;
    protected String name;
    protected String value;
    List<MutableTreeNode> children = new ArrayList();
    MutableTreeNode parent;

    public VariableNode(String name) {
        this.name = name;
        this.type = null;
        this.value= null;
    }

    public VariableNode(String name, String type) {
        this(name);
        this.type = type;
    }

    public VariableNode(String name, String type, Object value) {
        this(name, type);
        setValue(value);
    }

    public void setValue(Object value) {
        this.value = value.toString();
    }

    public void addChild(VariableNode c) {
        children.add(c);
        c.setParent(this);
    }

    @Override
    public TreeNode getChildAt(int i) {
       return children.get(i);
    }

    @Override
    public int getChildCount() {
        return children.size();
    }

    @Override
    public TreeNode getParent() {
        return parent;
    }

    @Override
    public int getIndex(TreeNode tn) {
        return children.indexOf(tn);
    }

    @Override
    public boolean getAllowsChildren() {
        return isLeaf();
    }

    @Override
    public boolean isLeaf() {
        return children.size() == 0;
    }

    @Override
    public Enumeration children() {
        return Collections.enumeration(children);
    }

    @Override
    public String toString() {
        String str = name;
        if (type != null) str += " (" + type + ")";
        if (value != null) str += ": " + value;
        return str;
    }

    @Override
    public void insert(MutableTreeNode mtn, int i) {
        children.add(i, this);
    }

    @Override
    public void remove(int i) {
        children.remove(i);
    }

    @Override
    public void remove(MutableTreeNode mtn) {
        children.remove(mtn);
        mtn.setParent(null);
    }

    @Override
    public void setUserObject(Object o) {
        setValue(o);
    }

    @Override
    public void removeFromParent() {
        parent.remove(this);
        this.parent = null;
    }

    @Override
    public void setParent(MutableTreeNode mtn) {
        parent = mtn;
    }
}