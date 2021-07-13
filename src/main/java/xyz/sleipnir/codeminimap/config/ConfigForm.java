package xyz.sleipnir.codeminimap.config;

import com.intellij.ui.JBColor;

import javax.swing.*;
import javax.swing.border.Border;
import java.util.regex.Pattern;

@SuppressWarnings("unchecked")
public class ConfigForm {
    private JPanel rootPanel;
    private JCheckBox chkDisabled;
    private JSpinner spnWidth;
    private JLabel lblDisabled;
    private JLabel lblPixelsPerLine;
    private JLabel lblWidth;
    private JComboBox cmbPixelsPerLine;
    private JComboBox cmbRenderStyle;
    private JLabel lblRenderStyle;
    private JCheckBox chkWidthLocked;
    private JLabel lblJumpToPositionOn;
    private JComboBox cmbJumpOn;
    private JLabel lblViewportColor;
    private JTextField txtViewportColor;
    private JComboBox cmbAlignment;
    private JLabel lblAlignment;
    private JLabel lblAlignmentInfo;
    private JCheckBox showCurrentLine;
    private JCheckBox showSelection;
    private JCheckBox showBookmarks;
    private JCheckBox showFindSymbols;
    private JCheckBox showChanges;
    private JTextField txtCurrentLineColor;
    private JTextField txtSelectionColor;
    private JTextField txtBookmarksColor;
    private JTextField txtFindSymbolsColor;
    private JTextField txtChangesColor;
    private JTextField txtChangesAddColor;
    private JTextField txtChangesDeleteColor;
    private JCheckBox showErrorsAndWarnings;
    private JTextField txtErrorsColor;
    private JTextField txtWarningsColor;

    public ConfigForm() {
        cmbPixelsPerLine.setModel(new DefaultComboBoxModel(new Integer[]{1, 2, 3, 4}));
        spnWidth.setModel(new SpinnerNumberModel(110, 50, 250, 5));
        txtViewportColor.setInputVerifier(new InputVerifier() {
            private final Pattern pattern = Pattern.compile("[a-fA-F0-9]{6}");
            private final Border defaultBorder = txtViewportColor.getBorder();
            private final Border invalidBorder = BorderFactory.createLineBorder(JBColor.RED);

            @Override
            public boolean verify(JComponent input) {
                boolean valid = pattern.matcher(txtViewportColor.getText()).matches();
                if (!valid)
                    txtViewportColor.setBorder(invalidBorder);
                else
                    txtViewportColor.setBorder(defaultBorder);
                return valid;
            }

            @Override
            public boolean shouldYieldFocus(JComponent input) {
                verify(input);
                return true;
            }
        });

        txtBookmarksColor.setInputVerifier(new InputVerifier() {
            private final Pattern pattern = Pattern.compile("[a-fA-F0-9]{6}");
            private final Border defaultBorder = txtBookmarksColor.getBorder();
            private final Border invalidBorder = BorderFactory.createLineBorder(JBColor.RED);

            @Override
            public boolean verify(JComponent input) {
                boolean valid = pattern.matcher(txtBookmarksColor.getText()).matches();
                if (!valid)
                    txtBookmarksColor.setBorder(invalidBorder);
                else
                    txtBookmarksColor.setBorder(defaultBorder);
                return valid;
            }

            @Override
            public boolean shouldYieldFocus(JComponent input) {
                verify(input);
                return true;
            }
        });

        txtCurrentLineColor.setInputVerifier(new InputVerifier() {
            private final Pattern pattern = Pattern.compile("[a-fA-F0-9]{6}");
            private final Border defaultBorder = txtCurrentLineColor.getBorder();
            private final Border invalidBorder = BorderFactory.createLineBorder(JBColor.RED);

            @Override
            public boolean verify(JComponent input) {
                boolean valid = pattern.matcher(txtCurrentLineColor.getText()).matches();
                if (!valid)
                    txtCurrentLineColor.setBorder(invalidBorder);
                else
                    txtCurrentLineColor.setBorder(defaultBorder);
                return valid;
            }

            @Override
            public boolean shouldYieldFocus(JComponent input) {
                verify(input);
                return true;
            }
        });

        txtSelectionColor.setInputVerifier(new InputVerifier() {
            private final Pattern pattern = Pattern.compile("[a-fA-F0-9]{6}");
            private final Border defaultBorder = txtSelectionColor.getBorder();
            private final Border invalidBorder = BorderFactory.createLineBorder(JBColor.RED);

            @Override
            public boolean verify(JComponent input) {
                boolean valid = pattern.matcher(txtSelectionColor.getText()).matches();
                if (!valid)
                    txtSelectionColor.setBorder(invalidBorder);
                else
                    txtSelectionColor.setBorder(defaultBorder);
                return valid;
            }

            @Override
            public boolean shouldYieldFocus(JComponent input) {
                verify(input);
                return true;
            }
        });

        txtChangesColor.setInputVerifier(new InputVerifier() {
            private final Pattern pattern = Pattern.compile("[a-fA-F0-9]{6}");
            private final Border defaultBorder = txtChangesColor.getBorder();
            private final Border invalidBorder = BorderFactory.createLineBorder(JBColor.RED);

            @Override
            public boolean verify(JComponent input) {
                boolean valid = pattern.matcher(txtChangesColor.getText()).matches();
                if (!valid)
                    txtChangesColor.setBorder(invalidBorder);
                else
                    txtChangesColor.setBorder(defaultBorder);
                return valid;
            }

            @Override
            public boolean shouldYieldFocus(JComponent input) {
                verify(input);
                return true;
            }
        });

        txtFindSymbolsColor.setInputVerifier(new InputVerifier() {
            private final Pattern pattern = Pattern.compile("[a-fA-F0-9]{6}");
            private final Border defaultBorder = txtFindSymbolsColor.getBorder();
            private final Border invalidBorder = BorderFactory.createLineBorder(JBColor.RED);

            @Override
            public boolean verify(JComponent input) {
                boolean valid = pattern.matcher(txtFindSymbolsColor.getText()).matches();
                if (!valid)
                    txtFindSymbolsColor.setBorder(invalidBorder);
                else
                    txtFindSymbolsColor.setBorder(defaultBorder);
                return valid;
            }

            @Override
            public boolean shouldYieldFocus(JComponent input) {
                verify(input);
                return true;
            }
        });

    }

    public JPanel getRoot() {
        return rootPanel;
    }

    public int getPixelsPerLine() {
        return (int) cmbPixelsPerLine.getSelectedItem();
    }

    public void setPixelsPerLine(int pixelsPerLine) {
        this.cmbPixelsPerLine.setSelectedIndex(pixelsPerLine - 1);
    }

    public boolean getRenderStyle() {
        return cmbRenderStyle.getSelectedIndex() == 0;
    }

    public void setRenderStyle(boolean isClean) {
        cmbRenderStyle.setSelectedIndex(isClean ? 0 : 1);
    }

    public String getViewportColor() {
        return txtViewportColor.getText();
    }

    public void setViewportColor(String viewportColor) {
        txtViewportColor.setText(viewportColor);
    }

    public boolean getJumpOn() {
        return cmbJumpOn.getSelectedIndex() == 0;
    }

    public void setJumpOn(boolean isMouseDown) {
        cmbJumpOn.setSelectedIndex(isMouseDown ? 0 : 1);
    }

    public boolean getAlignment() {
        return cmbAlignment.getSelectedIndex() == 0;
    }

    public void setAlignment(boolean isRightAligned) {
        cmbAlignment.setSelectedIndex(isRightAligned ? 0 : 1);
    }

    public int getWidth() {
        return (int) spnWidth.getValue();
    }

    public void setWidth(int width) {
        this.spnWidth.setValue(width);
    }

    public boolean isDisabled() {
        return chkDisabled.getModel().isSelected();
    }

    public void setDisabled(boolean isDisabled) {
        chkDisabled.getModel().setSelected(isDisabled);
    }

    public boolean isWidthLocked() {
        return chkWidthLocked.getModel().isSelected();
    }

    public void setWidthLocked(boolean isWidthLocked) {
        chkWidthLocked.getModel().setSelected(isWidthLocked);
    }

    public boolean isShowBookmarks() {
        return showBookmarks.getModel().isSelected();
    }

    public void setShowBookmarks(boolean isShowBookmarks) {
        showBookmarks.getModel().setSelected(isShowBookmarks);
    }

    public boolean isShowCurrentLine() {
        return showCurrentLine.getModel().isSelected();
    }

    public void setShowCurrentLine(boolean isShowCurrentLine) {
        showCurrentLine.getModel().setSelected(isShowCurrentLine);
    }

    public boolean isShowSelection() {
        return showSelection.getModel().isSelected();
    }

    public void setShowSelection(boolean isShowSelection) {
        showSelection.getModel().setSelected(isShowSelection);
    }

    public boolean isShowFindSymbols() {
        return showFindSymbols.getModel().isSelected();
    }

    public void setShowFindSymbols(boolean isShowFindSymbols) {
        showFindSymbols.getModel().setSelected(isShowFindSymbols);
    }

    public boolean isShowChanges() {
        return showChanges.getModel().isSelected();
    }

    public void setShowChanges(boolean isShowChanges) {
        showChanges.getModel().setSelected(isShowChanges);
    }

    public String getSelectionColor() {
        return txtSelectionColor.getText();
    }

    public void setSelectionColor(String selectionColor) {
        txtSelectionColor.setText(selectionColor);
    }

    public String getCurrentLineColor() {
        return txtCurrentLineColor.getText();
    }

    public void setCurrentLineColor(String currentLineColor) {
        txtCurrentLineColor.setText(currentLineColor);
    }

    public String getBookmarksColor() {
        return txtBookmarksColor.getText();
    }

    public void setBookmarksColor(String bookmarksColor) {
        txtBookmarksColor.setText(bookmarksColor);
    }

    public String getFindSymbolsColor() {
        return txtFindSymbolsColor.getText();
    }

    public void setFindSymbolsColor(String findSymbolsColor) {
        txtFindSymbolsColor.setText(findSymbolsColor);
    }

    public String getChangesColor() {
        return txtChangesColor.getText();
    }

    public void setChangesColor(String changesColor) {
        txtChangesColor.setText(changesColor);
    }

    public String getChangesAddColor() {
        return txtChangesAddColor.getText();
    }

    public void setChangesAddColor(String changesAddColor) {
        txtChangesAddColor.setText(changesAddColor);
    }

    public String getChangesDeleteColor() {
        return txtChangesDeleteColor.getText();
    }

    public void setChangesDeleteColor(String changesDeleteColor) {
        txtChangesDeleteColor.setText(changesDeleteColor);
    }

    public boolean isShowErrorsAndWarnings() {
        return showErrorsAndWarnings.getModel().isSelected();
    }

    public void setShowErrorsAndWarnings(boolean isShowErrorsAndWarnings) {
        showErrorsAndWarnings.getModel().setSelected(isShowErrorsAndWarnings);
    }

    public String getErrorsColor() {
        return txtErrorsColor.getText();
    }

    public void setErrorsColor(String errorsColor) {
        txtErrorsColor.setText(errorsColor);
    }

    public String getWarningsColor() {
        return txtWarningsColor.getText();
    }

    public void setWarningsColor(String warningsColor) {
        txtWarningsColor.setText(warningsColor);
    }
}
