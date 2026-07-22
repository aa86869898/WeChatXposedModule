package com.leshao.v3.dispatch;

import com.leshao.v3.model.ModuleConfig;
import com.leshao.v3.model.WeChatMessage;

public class Pipeline {

    public final String name;
    private final Handler handler;
    private Pipeline next;

    public interface Handler {
        boolean handle(WeChatMessage msg, ModuleConfig cfg);
    }

    public Pipeline(String name, Handler handler) {
        this.name = name;
        this.handler = handler;
    }

    public Pipeline setNext(Pipeline p) {
        this.next = p;
        return p;
    }

    public void process(WeChatMessage msg, ModuleConfig cfg) {
        boolean cont = handler.handle(msg, cfg);
        if (cont && next != null) {
            next.process(msg, cfg);
        }
    }
}
