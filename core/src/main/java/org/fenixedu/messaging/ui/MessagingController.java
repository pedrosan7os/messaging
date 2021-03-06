/*
 * @(#)MessagingAction.java
 *
 * Copyright 2012 Instituto Superior Tecnico
 * Founding Authors: Luis Cruz
 *
 *      https://fenix-ashes.ist.utl.pt/
 *
 *   This file is part of the Messaging Module.
 *
 *   The Messaging Module is free software: you can
 *   redistribute it and/or modify it under the terms of the GNU Lesser General
 *   Public License as published by the Free Software Foundation, either version
 *   3 of the License, or (at your option) any later version.
 *
 *   The Messaging Module is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *   GNU Lesser General Public License for more details.
 *
 *   You should have received a copy of the GNU Lesser General Public License
 *   along with the Messaging Module. If not, see <http://www.gnu.org/licenses/>.
 *
 */
package org.fenixedu.messaging.ui;

import java.util.Base64;
import java.util.Locale;
import java.util.Set;

import org.fenixedu.bennu.core.groups.Group;
import org.fenixedu.bennu.core.security.Authenticate;
import org.fenixedu.bennu.core.util.CoreConfiguration;
import org.fenixedu.bennu.spring.portal.SpringApplication;
import org.fenixedu.bennu.spring.portal.SpringFunctionality;
import org.fenixedu.messaging.domain.Message;
import org.fenixedu.messaging.domain.Sender;
import org.fenixedu.messaging.exception.MessagingDomainException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.view.RedirectView;

import com.google.common.base.Strings;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

@SpringApplication(path = "messaging", title = "title.messaging", group = "logged", hint = "Messaging")
@SpringFunctionality(app = MessagingController.class, title = "title.messaging.sending")
@RequestMapping("/messaging")
public class MessagingController {

    @RequestMapping(value = { "", "/" })
    public RedirectView redirectToSending() {
        return new RedirectView("/messaging/senders", true);
    }

    @RequestMapping(value = { "/senders", "/senders/" })
    public String listSenders(Model model, @RequestParam(value = "page", defaultValue = "1") int page, @RequestParam(
            value = "items", defaultValue = "10") int items) {
        PaginationUtils.paginate(model, "messaging/senders", "senders", Sender.available(), items, page);
        return "/messaging/listSenders";
    }

    @RequestMapping(value = "/senders/{sender}", method = RequestMethod.GET)
    public String viewSender(@PathVariable Sender sender, Model model,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "items", defaultValue = "10") int items) {
        if (!allowedSender(sender)) {
            throw MessagingDomainException.forbidden();
        }
        model.addAttribute("sender", sender);
        PaginationUtils.paginate(model, "messaging/senders/" + sender.getExternalId(), "messages", sender.getMessageSet(), items,
                page);
        return "/messaging/viewSender";
    }

    @RequestMapping(value = "/senders/{sender}", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody ResponseEntity<String> viewSenderInfo(@PathVariable Sender sender) {
        if (!allowedSender(sender)) {
            throw MessagingDomainException.forbidden();
        }

        Base64.Encoder encoder = Base64.getEncoder();
        JsonObject info = new JsonObject();
        JsonArray array = new JsonArray();
        for (Group g : sender.getRecipients()) {
            JsonObject group = new JsonObject();
            group.addProperty("name", g.getPresentationName());
            group.addProperty("expression", encoder.encodeToString(g.getExpression().getBytes()));
            array.add(group);
        }
        info.add("recipients", array);
        String replyTo = sender.getReplyTo();
        if (!Strings.isNullOrEmpty(replyTo)) {
            info.add("replyTo", new JsonPrimitive(replyTo));
        }
        info.addProperty("html", sender.getHtmlEnabled());
        return new ResponseEntity<String>(info.toString(), HttpStatus.OK);
    }

    @RequestMapping(value = "/message", method = RequestMethod.GET)
    public String newMessage(Model model, @ModelAttribute("messageBean") MessageBean messageBean) {
        if (messageBean.getSender() != null && !allowedSender(messageBean.getSender())) {
            throw MessagingDomainException.forbidden();
        }
        if (messageBean.getSender() == null) {
            messageBean.setSender(Sender.available().stream().findAny().orElse(null));
        }
        model.addAttribute("messageBean", messageBean);
        return "/messaging/newMessage";
    }

    @RequestMapping(value = "/message", method = RequestMethod.POST)
    public ModelAndView sendMessage(Model model, @ModelAttribute("messageBean") MessageBean messageBean,
            RedirectAttributes redirectAttributes) {
        if (messageBean != null) {
            if (allowedSender(messageBean.getSender())) {
                Message message = messageBean.send();
                if (message != null) {
                    redirectAttributes.addFlashAttribute("justCreated", true);
                    return new ModelAndView(new RedirectView("/messaging/messages/" + message.getExternalId(), true));
                }
            } else {
                throw MessagingDomainException.forbidden();
            }
        }
        return new ModelAndView(newMessage(model, messageBean));
    }

    @RequestMapping(value = "/messages/{message}", method = RequestMethod.GET)
    public String viewMessage(Model model, @PathVariable Message message, boolean created) {
        if (!allowedSender(message.getSender())) {
            throw MessagingDomainException.forbidden();
        }
        model.addAttribute("locales", getSupportedLocales(message));
        model.addAttribute("deletable", isMessageDeletable(message));
        model.addAttribute("message", message);
        return "/messaging/viewMessage";
    }

    private Set<Locale> getSupportedLocales(Message message) {
        Set<Locale> locales = message.getContentLocales();
        locales.addAll(CoreConfiguration.supportedLocales());
        locales.add(message.getPreferredLocale());
        return locales;
    }

    @RequestMapping(value = "/messages/{message}/delete", method = RequestMethod.POST)
    public String deleteMessage(@PathVariable Message message, Model model) {
        Sender sender = message.getSender();
        userMessageDelete(message);
        return viewSender(sender, model, 1, 10);
    }

    private void userMessageDelete(Message message) {
        if (isMessageDeletable(message)) {
            message.delete();
        } else if (!isLoggedUserCreator(message)) {
            throw MessagingDomainException.forbidden();
        }
    }

    private boolean isMessageDeletable(Message message) {
        return isLoggedUserCreator(message) && message.getDispatchReport() == null;
    }

    private boolean isLoggedUserCreator(Message message) {
        return message.getUser().equals(Authenticate.getUser());
    }

    private boolean allowedSender(Sender sender) {
        return sender.getMembers().isMember(Authenticate.getUser());
    }

}
