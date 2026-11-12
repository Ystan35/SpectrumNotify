// Vercel function, called every minute by a pinger (a free scheduler such as cron-job.org; Vercel's own free cron runs once a day).
// The secret comes as "Authorization: Bearer <TICK_SECRET>" or, for pingers that cannot set headers, as ?key=<TICK_SECRET>.
module.exports = async (req, res) => {
  const bearer = (req.headers.authorization || "").replace(/^Bearer\s+/i, "");
  const r = await require("../server/default.js").tick(bearer || (req.query && req.query.key) || "");
  res.status(r.status).json(r.body);
};
