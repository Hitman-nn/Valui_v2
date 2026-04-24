package BotValui.components;

import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

public class Menu {
    private final LinkedList<Button> buttonList;
    private final String title;
    private final int row;

    public Menu(String title, LinkedList<Button> buttonList, int row) {
        this.title = title;
        this.buttonList = buttonList;
        this.row = row;
    }

    public List<InlineKeyboardMarkup> drawMenu() {
        List<InlineKeyboardMarkup> inlineKeyboardButtonList = new ArrayList<>();
        ArrayList<InlineKeyboardButton> buttonArrayList = new ArrayList<>();
        buttonList.forEach(button -> {
            InlineKeyboardButton buttonKeyboard = new InlineKeyboardButton(button.getTitle(), "", button.getData(),
                    null, "", "", false, null, null);
            buttonArrayList.add(buttonKeyboard);
        });
        List<InlineKeyboardButton> rowInlineList = new ArrayList<>();
        List<List<InlineKeyboardButton>> rowsInlineList = new ArrayList<>();
        int i = row;
        int j = 1;
        for (InlineKeyboardButton button : buttonArrayList) {
            if (i == 0) {
                rowsInlineList.add(rowInlineList);
                rowInlineList = new ArrayList<>();
                i = row;
            }
            rowInlineList.add(button);
            --i;
            ++j;
            if (j > 50) {
                j = 1;
                rowsInlineList.add(rowInlineList);
                InlineKeyboardMarkup markupInline = new InlineKeyboardMarkup();
                markupInline.setKeyboard(rowsInlineList);
                inlineKeyboardButtonList.add(markupInline);
                rowInlineList = new ArrayList<>();
                rowsInlineList = new ArrayList<>();
            }
        }
        rowsInlineList.add(rowInlineList);

        InlineKeyboardMarkup markupInline = new InlineKeyboardMarkup();
        markupInline.setKeyboard(rowsInlineList);
        inlineKeyboardButtonList.add(markupInline);
        return inlineKeyboardButtonList;
    }

    public String getTitle() {
        return title;
    }

    public int getRow() {
        return row;
    }

    public LinkedList<Button> getButtonList() {
        return buttonList;
    }
}
