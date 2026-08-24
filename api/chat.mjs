const SYSTEM_PROMPT = `Você é OverGo, uma IA especializada em Pokémon e especialmente Pokémon GO.

Regras de resposta:
- Responda em português do Brasil, a menos que o usuário peça outro idioma.
- Seja útil, direto e estratégico. Não responda como uma Pokédex seca.
- Quando houver coleção do usuário no contexto, use os Pokémon, CPs e IVs reais daquela coleção.
- Diferencie Pokémon GO dos jogos principais. Se a pergunta for claramente sobre GO, use mecânicas de GO.
- Para eventos, raids, rotações, disponibilidade, mudanças de balanceamento ou outras informações atuais, use busca web antes de afirmar algo atual.
- Se houver incerteza factual, deixe isso claro.
- Não peça senha, token, cookie ou login da conta Pokémon GO.
- Não ensine spoofing, automação, bypass de anti-cheat, acesso a API privada ou qualquer método para manipular o jogo.
- Você pode montar equipes, avaliar Pokémon, explicar IV/CP, tipos, counters, raids, PvP, PvE, evolução, recursos e decisões sobre a coleção.
- Quando comparar Pokémon da coleção, cite CP e IV relevantes.
- Evite respostas genéricas. Termine com uma recomendação prática quando fizer sentido.`;

export default async function handler(req, res) {
  if (req.method !== "POST") {
    res.status(405).json({ error: "method_not_allowed" });
    return;
  }

  if (!process.env.OPENAI_API_KEY) {
    res.status(503).json({ error: "ai_not_configured" });
    return;
  }

  try {
    const body = typeof req.body === "string" ? JSON.parse(req.body) : (req.body || {});
    const question = String(body.question || "").trim();
    const collection = Array.isArray(body.collection) ? body.collection.slice(0, 250) : [];
    const history = Array.isArray(body.history) ? body.history.slice(-16) : [];

    if (!question) {
      res.status(400).json({ error: "empty_question" });
      return;
    }

    const safeCollection = collection.map((item) => ({
      pokemon: String(item?.pokemon || "").slice(0, 60),
      cp: Number.isFinite(Number(item?.cp)) ? Number(item.cp) : null,
      iv: Number.isFinite(Number(item?.iv)) ? Number(item.iv) : null,
    })).filter((item) => item.pokemon);

    const input = [
      { role: "system", content: SYSTEM_PROMPT },
      {
        role: "system",
        content: `Coleção local do usuário (pode estar vazia): ${JSON.stringify(safeCollection)}`,
      },
    ];

    for (const turn of history) {
      const role = turn?.role === "assistant" ? "assistant" : "user";
      const content = String(turn?.content || "").slice(0, 4000).trim();
      if (content) input.push({ role, content });
    }
    input.push({ role: "user", content: question });

    const response = await fetch("https://api.openai.com/v1/responses", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${process.env.OPENAI_API_KEY}`,
      },
      body: JSON.stringify({
        model: process.env.OPENAI_MODEL || "gpt-5.6",
        store: false,
        tools: [{ type: "web_search" }],
        input,
        max_output_tokens: 900,
      }),
    });

    const data = await response.json();
    if (!response.ok) {
      console.error("OpenAI error", response.status, data);
      res.status(502).json({ error: "model_error" });
      return;
    }

    const answer = extractOutputText(data);
    if (!answer) {
      res.status(502).json({ error: "empty_model_response" });
      return;
    }

    res.status(200).json({ answer });
  } catch (error) {
    console.error(error);
    res.status(500).json({ error: "server_error" });
  }
}

function extractOutputText(data) {
  if (typeof data?.output_text === "string" && data.output_text.trim()) {
    return data.output_text.trim();
  }
  const chunks = [];
  for (const item of data?.output || []) {
    if (item?.type !== "message") continue;
    for (const content of item?.content || []) {
      if (content?.type === "output_text" && typeof content?.text === "string") {
        chunks.push(content.text);
      }
    }
  }
  return chunks.join("\n").trim();
}
