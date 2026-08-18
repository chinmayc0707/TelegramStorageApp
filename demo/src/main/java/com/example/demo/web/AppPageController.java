package com.example.demo.web;

import com.example.demo.auth.LoginStep;
import com.example.demo.auth.TelegramSessionRegistry;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AppPageController {

    private final TelegramSessionRegistry sessions;

    public AppPageController(TelegramSessionRegistry sessions) {
        this.sessions = sessions;
    }

    @GetMapping("/")
    public String landing(HttpSession session) {
        return signedIn(session) ? "redirect:/home" : "redirect:/login";
    }

    @GetMapping("/login")
    public String login(HttpSession session) {
        return signedIn(session) ? "redirect:/home" : "forward:/index.html";
    }

    @GetMapping({"/home", "/home/**"})
    public String home(HttpSession session) {
        return signedIn(session) ? "forward:/index.html" : "redirect:/login";
    }

    @GetMapping({"/drive", "/drive/**"})
    public String oldDriveAddress() {
        return "redirect:/home";
    }

    private boolean signedIn(HttpSession session) {
        return sessions.snapshot(session.getId()).step() == LoginStep.READY;
    }
}
