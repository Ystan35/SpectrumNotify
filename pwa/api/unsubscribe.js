// Vercel function: POST { endpoint }
module.exports = async (req, res) => {
  if (req.method !== "POST") return res.status(405).json({ error: "POST only" });
  const r = await require("../server/default.js").unsubscribe(req.body);
  res.status(r.status).json(r.body);
};
