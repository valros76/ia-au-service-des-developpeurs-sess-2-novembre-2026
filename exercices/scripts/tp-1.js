// Run: GROQ_API_KEY="gsk_..." node benchmark-tokens.js

const API_URL = "https://api.groq.com/openai/v1/chat/completions";
const MODEL = "openai/gpt-oss-120b";

// Groq pricing table for openai/gpt-oss-120b ($ per 1M tokens)
const INPUT_PRICE_PER_1M = 0.15;
const OUTPUT_PRICE_PER_1M = 0.60;
const USD_TO_EUR_RATE = 0.92;

const verbosePrompt = `Bonjour ! J'espère que tu vas bien. Pourrais-tu s'il te plaît m'écrire une fonction en JavaScript moderne qui me permettrait de vérifier si une adresse email passée en paramètre est valide ou non ? J'aimerais que tu utilises une expression régulière simple, que tu commentes bien le code et que tu me donnes un petit exemple d'utilisation à la fin. Merci beaucoup par avance pour ton aide !`;

const concisePrompt = `Rôle: Développeur JS.
Tâche: Écris une fonction isValidEmail(email) utilisant une regex simple.
Contraintes: Code commenté + 1 exemple d'appel. Pas de blabla.`;

async function measureApiCall(label, prompt) {
  const startTime = performance.now();

  const response = await fetch(API_URL, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "Authorization": `Bearer ${process.env.GROQ_API_KEY}`
    },
    body: JSON.stringify({
      model: MODEL,
      messages: [
        {role: "system", content: "Rôle: Développeur JS. Contraintes: Code commenté + 1 exemple d'appel. Pas de blabla."},
        { role: "user", content: prompt }
      ],
      temperature: 0.0
    })
  });

  if (!response.ok) {
    throw new Error(`API Error (${response.status}): ${await response.text()}`);
  }

  const data = await response.json();
  const durationMs = (performance.now() - startTime).toFixed(2);

  const promptTokens = data.usage.prompt_tokens;
  const completionTokens = data.usage.completion_tokens;

  const costUsd =
    (promptTokens / 1_000_000) * INPUT_PRICE_PER_1M +
    (completionTokens / 1_000_000) * OUTPUT_PRICE_PER_1M;
  const costEur = costUsd * USD_TO_EUR_RATE;

  return {
    Version: label,
    "Time (ms)": durationMs,
    "Input Tokens": promptTokens,
    "Output Tokens": completionTokens,
    "Cost ($)": `$${costUsd.toFixed(6)}`,
    "Cost (€)": `${costEur.toFixed(6)} €`
  };
}

export async function runBenchmark() {
  if (!process.env.GROQ_API_KEY) {
    console.error("Error: please set the GROQ_API_KEY environment variable.");
    process.exit(1);
  }

  const verboseResult = await measureApiCall("Verbose prompt", verbosePrompt);
  const conciseResult = await measureApiCall("Concise prompt", concisePrompt);

  console.table([verboseResult, conciseResult]);
}