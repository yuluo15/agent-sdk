package com.agent.agentcorelab.model;

import com.agent.agentcorelab.tool.ToolDefinition;

import java.util.List;

public interface ModelGateway {

    ModelSession openSession(String instructions, String goal, List<ToolDefinition> tools);
}
