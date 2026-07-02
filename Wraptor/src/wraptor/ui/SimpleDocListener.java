package wraptor.ui;

import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/** Collapses insert/remove/changed into a single callback for live-binding JTextFields to the model. */
class SimpleDocListener implements DocumentListener {
    interface Callback { void run(); }
    private final Callback callback;

    SimpleDocListener(Callback callback) { this.callback = callback; }

    @Override public void insertUpdate(DocumentEvent e) { callback.run(); }
    @Override public void removeUpdate(DocumentEvent e) { callback.run(); }
    @Override public void changedUpdate(DocumentEvent e) { callback.run(); }
}
